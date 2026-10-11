package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.entity.TagEntity
import ai.dusty.finderplus.model.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wipes the derived index so it can be rebuilt from scratch, for changes that invalidate all of it
 * (e.g. a new CLIP encoder, whose vectors have the same shape but are not comparable). Ordinary
 * changes use pass-version bumps instead.
 *
 * User media is never touched, and neither are the rows of vaulted files: they are the only record
 * of where each hidden file belongs. User labels are kept by default because they cannot be
 * recomputed; they are re-applied after the rescan, which works because `media_item.id` is the
 * MediaStore id and survives a rebuild.
 */
@Singleton
class IndexReset @Inject constructor(
    private val db: FinderDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {

    data class Report(
        val itemsCleared: Int,
        val userLabelsPreserved: Int,
        val userLabelsRestored: Int,
    )

    /** A user label held across the wipe. */
    private data class SavedTag(val itemId: Long, val label: String, val confidence: Float, val source: Int)

    private var saved: List<SavedTag> = emptyList()

    /**
     * Labels held across a wipe. Kept on disk so they survive the process dying mid-rescan or a second
     * reset starting early. Deleted once every label is back; labels whose items have not reappeared
     * stay for the next attempt.
     */
    private val savedFile get() = java.io.File(context.filesDir, "reset-user-labels.tsv")

    private fun persist(tags: List<SavedTag>) {
        val f = savedFile
        if (tags.isEmpty()) { f.delete(); return }
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for (t in tags) w.write("${t.itemId}\t${t.source}\t${t.confidence}\t${t.label.replace('\t', ' ').replace('\n', ' ')}\n")
        }
        tmp.renameTo(f)
    }

    private fun load(): List<SavedTag> = runCatching {
        savedFile.takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            if (p.size < 4) null else SavedTag(p[0].toLong(), p[3], p[2].toFloat(), p[1].toInt())
        }
    }.getOrNull().orEmpty()

    /**
     * Delete every derived row. Call [restoreUserLabels] after the next scan to put preserved labels
     * back.
     *
     * @param keepUserLabels when true, USER-source tags survive the wipe.
     */
    suspend fun wipe(keepUserLabels: Boolean = true): Report {
        val itemCount = db.mediaItemDao().count()

        saved = if (keepUserLabels) {
            // Merged with any labels a previous, unfinished reset is still holding.
            (load() + db.contentDao().tagsBySource(TagSource.USER.ordinal)
                .map { SavedTag(it.item_id, it.label, it.confidence, it.source) }).distinct()
        } else {
            emptyList()
        }
        if (keepUserLabels) persist(saved)

        // FTS is a virtual table outside the foreign-key cascade, so it is cleared separately.
        db.ftsDao().clearAll()
        // Deleting media_item cascades to work_unit, tag, document, segment, embedding, face and
        // media_profile. Vaulted rows are exempt (see deleteAll): no rescan can recreate them.
        db.mediaItemDao().deleteAll()
        db.labelPrototypeDao().deleteAll()
        db.indexRunDao().deleteAll()
        // Answers to label questions are the user's supervision and follow the same choice as their labels.
        if (keepUserLabels) db.labelQuestionDao().clearAllPending() else {
            db.labelQuestionDao().clearAll()
            db.nameRankingDao().clearAll()
        }
        // Vaulted items lost their FTS rows above and a rescan never revisits them; rebuild them so
        // hidden files stay searchable.
        val finalizer = ItemFinalizer(db)
        for (row in db.mediaItemDao().vaulted()) runCatching { finalizer.rebuildSearch(row.id) }

        return Report(
            itemsCleared = itemCount,
            userLabelsPreserved = saved.size,
            userLabelsRestored = 0,
        )
    }

    /**
     * Re-apply labels held by [wipe]. Labels whose item has not reappeared yet stay in the file for
     * the next restore or reset.
     */
    suspend fun restoreUserLabels(): Int {
        if (saved.isEmpty()) saved = load()
        if (saved.isEmpty()) return 0
        var restored = 0
        val waiting = ArrayList<SavedTag>()
        for (tag in saved) {
            if (db.mediaItemDao().byId(tag.itemId) == null) { waiting += tag; continue }
            db.contentDao().insertTags(
                listOf(
                    TagEntity(
                        item_id = tag.itemId,
                        source = tag.source,
                        label = tag.label,
                        confidence = tag.confidence,
                    )
                )
            )
            restored++
        }
        persist(waiting)
        saved = emptyList()
        return restored
    }
}

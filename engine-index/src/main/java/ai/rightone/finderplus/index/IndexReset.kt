package ai.rightone.finderplus.index

import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.entity.TagEntity
import ai.rightone.finderplus.model.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wipes the derived index so it can be rebuilt from scratch.
 *
 * Needed because some changes invalidate the whole index rather than part of it — swapping the CLIP
 * encoder is the clearest case, since old and new vectors are the same shape and silently incomparable.
 * Pass-version bumps handle the ordinary case; this handles "start over".
 *
 * Nothing here touches the user's media. Only rows this app derived are deleted.
 *
 * By default the user's **own labels are preserved**, because they are the one thing in the database
 * that cannot be recomputed: every tag, embedding and transcript can be rebuilt by spending CPU, but a
 * name someone typed exists nowhere else. They are read out before the wipe and re-applied after the
 * rescan restores their items — safe because `media_item.id` is the MediaStore id, so an item keeps
 * its identity across a rebuild.
 */
@Singleton
class IndexReset @Inject constructor(private val db: FinderDatabase) {

    data class Report(
        val itemsCleared: Int,
        val userLabelsPreserved: Int,
        val userLabelsRestored: Int,
    )

    /** A user label held across the wipe. */
    private data class SavedTag(val itemId: Long, val label: String, val confidence: Float, val source: Int)

    private var saved: List<SavedTag> = emptyList()

    /**
     * Delete every derived row. Call [restoreUserLabels] after the next scan to put preserved labels
     * back.
     *
     * @param keepUserLabels when true, USER-source tags survive the wipe.
     */
    suspend fun wipe(keepUserLabels: Boolean = true): Report {
        val itemCount = db.mediaItemDao().count()

        saved = if (keepUserLabels) {
            db.contentDao().tagsBySource(TagSource.USER.ordinal)
                .map { SavedTag(it.item_id, it.label, it.confidence, it.source) }
        } else {
            emptyList()
        }

        // Order matters only for the FTS table, which is a virtual table outside Room's entity graph
        // and therefore not covered by the foreign-key cascade that clears everything else.
        db.ftsDao().clearAll()
        // Deleting media_item cascades to work_unit, tag, document, segment, embedding, face and
        // media_profile via ON DELETE CASCADE, so one statement clears the derived index.
        db.mediaItemDao().deleteAll()
        db.labelPrototypeDao().deleteAll()
        db.indexRunDao().deleteAll()

        return Report(
            itemsCleared = itemCount,
            userLabelsPreserved = saved.size,
            userLabelsRestored = 0,
        )
    }

    /**
     * Re-apply labels held by [wipe]. Items missing from the new scan (deleted from the gallery in the
     * meantime) are dropped rather than resurrected — a tag with no media is not something the user can
     * ever see or act on.
     */
    suspend fun restoreUserLabels(): Int {
        if (saved.isEmpty()) return 0
        var restored = 0
        for (tag in saved) {
            if (db.mediaItemDao().byId(tag.itemId) == null) continue
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
        saved = emptyList()
        return restored
    }
}

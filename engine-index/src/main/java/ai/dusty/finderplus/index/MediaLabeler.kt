package ai.rightone.finderplus.index

import androidx.room.withTransaction
import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.entity.TagEntity
import ai.rightone.finderplus.model.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-supplied labels for one media item.
 *
 * These are the highest-precision signal in the whole index: a person naming their own photo beats any
 * model. They are stored under [TagSource.USER] so they are never overwritten by a pass re-running —
 * every AI pass clears only its *own* source — and the item's search artifacts are rebuilt immediately
 * so a label is searchable the moment it is typed, without waiting for an index run.
 */
@Singleton
class MediaLabeler @Inject constructor(
    private val db: FinderDatabase,
    private val learner: LabelLearner,
) {

    /** Resolve an incoming content URI to an indexed item. MediaStore ids are the URI's last segment. */
    suspend fun resolveItem(uri: String): Long? {
        val id = uri.trimEnd('/').substringAfterLast('/').toLongOrNull() ?: return null
        return db.mediaItemDao().byId(id)?.id
    }

    suspend fun userLabels(itemId: Long): List<String> =
        db.contentDao().tagsForItem(itemId)
            .filter { it.source == TagSource.USER.ordinal }
            .map { it.label }

    /** Replace this item's user labels, then refresh its FTS row and AI-revision profile. */
    suspend fun setLabels(itemId: Long, labels: List<String>) {
        val cleaned = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(32)
        db.withTransaction {
            db.contentDao().clearTags(itemId, TagSource.USER.ordinal)
            if (cleaned.isNotEmpty()) {
                db.contentDao().insertTags(cleaned.map {
                    TagEntity(item_id = itemId, source = TagSource.USER.ordinal, label = it, confidence = 1f)
                })
            }
            ItemFinalizer(db).rebuildSearch(itemId)
        }
        // Teach the label from this item's embedding so it can propagate to visually similar media.
        // Returns false when no usable embedding exists yet (e.g. CLIP not installed) — labelling still
        // works, it just cannot generalize until embeddings are available.
        if (cleaned.isNotEmpty()) learner.learn(itemId, cleaned)
    }

    suspend fun displayName(itemId: Long): String? = db.mediaItemDao().byId(itemId)?.display_name
}

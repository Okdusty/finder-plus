package ai.rightone.finderplus.index

import androidx.room.withTransaction
import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.media.IndexedSignature
import ai.rightone.finderplus.media.MediaDiffer
import ai.rightone.finderplus.media.MediaStoreReader
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass

data class ScanSummary(
    val added: Int,
    val changed: Int,
    val removed: Int,
    val generation: Long,
    /** Work units created for passes added to the pipeline after these items were first scanned. */
    val backfilled: Int = 0,
)

/**
 * Incremental MediaStore diff → enqueue new/changed passes, purge deleted. A "changed" file is
 * treated as remove+add so no stale derived rows survive; purge cascades via FK to work_units, tags,
 * documents, segments, and embeddings. All operations idempotent. See docs/design/01-DB-ENGINE.md §8.
 */
internal class Scanner(
    private val reader: MediaStoreReader,
    private val db: FinderDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun scan(runId: Long): ScanSummary {
        val generation = reader.currentGeneration()
        val live = reader.digest()
        val known = db.mediaItemDao().inventoryDigest()
            .map { IndexedSignature(it.id, it.date_modified, it.media_generation, it.size_bytes) }

        val diff = MediaDiffer.diff(live, known)

        // Bulk-read every item once (3 queries) instead of one query per added item, then insert
        // added + changed in chunked transactions to avoid per-row fsync. This is the difference
        // between a first index taking minutes of scan vs seconds.
        if (diff.added.isNotEmpty() || diff.changed.isNotEmpty()) {
            val byId = reader.readAll().associateBy { it.id }
            // Changed = remove (cascade) + re-add, so no stale derived rows survive.
            db.withTransaction {
                for (d in diff.changed) {
                    db.ftsDao().deleteRow(d.id)
                    db.mediaItemDao().purge(d.id)
                }
            }
            (diff.added + diff.changed).chunked(400).forEach { chunk ->
                db.withTransaction {
                    for (d in chunk) {
                        val item = byId[d.id]
                        if (item != null) {
                            db.mediaItemDao().upsert(item.toEntity(now(), d.generation))
                            db.workUnitDao().enqueue(passUnitsFor(d.id, d.kind, now()))
                        }
                    }
                }
            }
        }

        for (id in diff.removedIds) {
            db.ftsDao().deleteRow(id)
            db.mediaItemDao().purge(id)
        }

        val backfilled = backfillNewPasses()

        db.indexRunDao().setGeneration(runId, generation)
        return ScanSummary(diff.added.size, diff.changed.size, diff.removedIds.size, generation, backfilled)
    }

    /**
     * Extend the existing index when a pass is ADDED to the pipeline after items were first scanned
     * (OBJECTS, for example). Without this, only newly-discovered files would ever get the new pass and
     * the only way to apply it to the rest of the gallery would be a destructive full re-index — losing
     * all completed AI work. Enqueue is `INSERT OR IGNORE` on `UNIQUE(item_id, pass)`, so this is
     * idempotent and a no-op once every item has the pass.
     */
    private suspend fun backfillNewPasses(): Int {
        var total = 0
        for (pass in Pass.entries) {
            val missing = db.mediaItemDao().itemsMissingPass(pass.ordinal)
                // Only kinds that actually run this pass (an audio file must not get OBJECTS).
                .filter { pass in Pass.forKind(MediaKind.entries[it.kind]) }
            if (missing.isEmpty()) continue
            missing.chunked(400).forEach { chunk ->
                db.withTransaction {
                    for (row in chunk) {
                        db.workUnitDao().enqueue(
                            passUnitsFor(row.id, MediaKind.entries[row.kind], now())
                                .filter { it.pass == pass.ordinal }
                        )
                    }
                }
            }
            total += missing.size
        }
        return total
    }
}

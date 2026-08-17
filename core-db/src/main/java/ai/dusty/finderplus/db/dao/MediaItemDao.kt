package ai.rightone.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import ai.rightone.finderplus.db.entity.MediaItemEntity

@Dao
interface MediaItemDao {

    @Upsert
    suspend fun upsert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: MediaItemEntity): Long

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    /** Lightweight projection for the incremental diff — only the change-detection columns. */
    /**
     * The scan diff's "known" set — deliberately **excluding vaulted rows**.
     *
     * A vaulted file has left MediaStore on purpose, so a differ that still counted it as known
     * would classify it as removed and [purge] it, cascading away every tag, caption, embedding and
     * vote the pipeline spent hours producing. Excluded here, a vaulted item is invisible to the
     * diff in both directions: absent from `live` because it is not in MediaStore, absent from
     * `known` because of this clause — so it is never added and never removed, and its row survives
     * untouched until [unvaultItem] brings it back.
     */
    @Query("SELECT id, date_modified, media_generation, size_bytes FROM media_item WHERE deleted = 0 AND original_path IS NULL")
    suspend fun inventoryDigest(): List<InventoryRow>

    /**
     * Items that have no work unit for [pass] yet. Used to backfill a pass that was added to the
     * pipeline after these items were first scanned (e.g. OBJECTS), so the existing index is extended
     * in place instead of being wiped and rebuilt.
     */
    @Query(
        """
        SELECT id, kind FROM media_item
        WHERE deleted = 0
          AND id NOT IN (SELECT item_id FROM work_unit WHERE pass = :pass)
        """
    )
    suspend fun itemsMissingPass(pass: Int): List<IdKind>

    @Query("UPDATE media_item SET index_state = :state, last_scanned_at = :now WHERE id = :id")
    suspend fun setState(id: Long, state: Int, now: Long)

    @Query("UPDATE media_item SET place = :place, lat = :lat, lon = :lon WHERE id = :id")
    suspend fun setGeo(id: Long, place: String?, lat: Double?, lon: Double?)

    @Query(
        "UPDATE media_item SET pipeline_version = :version WHERE id = :id"
    )
    suspend fun setPipelineVersion(id: Long, version: Int)

    /** Mark a changed file STALE and record the new signature; its work units are reset separately. */
    @Query(
        "UPDATE media_item SET index_state = 4, date_modified = :dateModified, media_generation = :generation, size_bytes = :size, last_scanned_at = :now WHERE id = :id"
    )
    suspend fun markStale(id: Long, dateModified: Long, generation: Long, size: Long, now: Long)

    @Query("UPDATE media_item SET deleted = 1 WHERE id = :id")
    suspend fun tombstone(id: Long)

    /** Cascades to tags/documents/segments/embeddings/work_units via FK ON DELETE CASCADE. */
    @Query("DELETE FROM media_item WHERE id = :id")
    suspend fun purge(id: Long)

    // ---- vault ----

    /** The file moved into the vault: repoint access to its file URI, remember where it came from. */
    @Query("UPDATE media_item SET content_uri = :fileUri, original_path = :originalPath WHERE id = :id")
    suspend fun vaultItem(id: Long, fileUri: String, originalPath: String)

    /** The file moved back and rejoined MediaStore under a fresh row. */
    @Query("UPDATE media_item SET content_uri = :contentUri, original_path = NULL WHERE id = :id")
    suspend fun unvaultItem(id: Long, contentUri: String)

    @Query("SELECT * FROM media_item WHERE original_path IS NOT NULL AND deleted = 0")
    suspend fun vaulted(): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_item WHERE original_path IS NOT NULL AND deleted = 0")
    suspend fun vaultedCount(): Int

    /**
     * After a restore the file re-enters MediaStore under a NEW id; every derived row must follow,
     * or hours of AI work orphan. One transaction, children first, parent last.
     */
    @androidx.room.Transaction
    suspend fun rekeyItem(oldId: Long, newId: Long) {
        rekey("tag", oldId, newId); rekey("document", oldId, newId); rekey("segment", oldId, newId)
        rekey("embedding", oldId, newId); rekey("work_unit", oldId, newId)
        rekey("media_profile", oldId, newId); rekey("face", oldId, newId)
        rekeyParent(oldId, newId)
    }

    @Query("UPDATE media_item SET id = :newId WHERE id = :oldId")
    suspend fun rekeyParent(oldId: Long, newId: Long)

    @androidx.room.RawQuery
    suspend fun rawUpdate(query: androidx.sqlite.db.SupportSQLiteQuery): Long

    private suspend fun rekey(table: String, oldId: Long, newId: Long) {
        rawUpdate(
            androidx.sqlite.db.SimpleSQLiteQuery(
                "UPDATE $table SET item_id = ? WHERE item_id = ?", arrayOf(newId, oldId),
            )
        )
    }

    @Query("SELECT COUNT(*) FROM media_item WHERE deleted = 0")
    suspend fun count(): Int

    /** Full wipe. Cascades to every derived table via ON DELETE CASCADE. */
    @Query("DELETE FROM media_item")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM media_item WHERE deleted = 0 AND kind = :kind")
    suspend fun countKind(kind: Int): Int

    /** Any one item of [kind] — used by the debug speech spot-check. Prefers longer files. */
    @Query("SELECT * FROM media_item WHERE deleted = 0 AND kind = :kind ORDER BY duration_ms DESC LIMIT 1")
    suspend fun firstOfKind(kind: Int): MediaItemEntity?

    /** Review candidates: embedded, not already labelled by the user, newest first. */
    @Query(
        """
        SELECT m.id FROM media_item m
        WHERE m.deleted = 0
          AND EXISTS (SELECT 1 FROM embedding e WHERE e.item_id = m.id AND e.kind = 0)
          AND NOT EXISTS (SELECT 1 FROM tag t WHERE t.item_id = m.id AND t.source = 4)
        ORDER BY m.date_taken DESC LIMIT :limit
        """
    )
    suspend fun itemsWithEmbeddingNoUserLabel(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM media_item WHERE deleted = 0 AND index_state = 3")
    suspend fun countFailed(): Int

    /**
     * Items whose derived search artifacts (AI-revision profile + FTS row) were built by an older
     * format and need re-deriving. This is a cheap sweep — it re-reads existing tags/OCR/transcript/
     * faces, so no AI pass re-runs; it exists because items that finished before a profile-format
     * change would otherwise keep a stale profile forever.
     */
    @Query(
        """
        SELECT id FROM media_item
        WHERE deleted = 0 AND pipeline_version < :version
          AND EXISTS (SELECT 1 FROM work_unit w WHERE w.item_id = media_item.id AND w.state = 3)
        ORDER BY id LIMIT :limit
        """
    )
    suspend fun itemsWithStaleArtifacts(version: Int, limit: Int): List<Long>

    /**
     * Items whose search artifacts are older than the current format.
     *
     * Keyed on "has at least one completed pass" rather than on `index_state`, which was circular and
     * silently disabled this repair path: `index_state` is written by `projectState`, which only runs
     * alongside the rebuild being selected for here. An item whose rebuild never happened therefore
     * stayed at `NEW = 0`, was excluded by `index_state != 0`, and could never be repaired. Measured on a
     * live index: 4,845 of 4,847 items sat at state 0 with zero profiles and zero FTS rows, and this
     * query returned nothing for all of them.
     */
    @Query(
        """
        SELECT COUNT(*) FROM media_item
        WHERE deleted = 0 AND pipeline_version < :version
          AND EXISTS (SELECT 1 FROM work_unit w WHERE w.item_id = media_item.id AND w.state = 3)
        """
    )
    suspend fun countStaleArtifacts(version: Int): Int
}

data class InventoryRow(
    val id: Long,
    val date_modified: Long,
    val media_generation: Long,
    val size_bytes: Long,
)

/** (item id, media kind ordinal) — enough to enqueue the right passes for an item. */
data class IdKind(val id: Long, val kind: Int)

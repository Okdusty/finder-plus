package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction

/**
 * Access to the FTS4 virtual tables `media_fts` and `caption_fts`. Room has no FTS-entity annotation
 * for a manually-created table, so they are created by [ai.dusty.finderplus.db.FinderDatabase]'s
 * callback and these queries opt out of compile-time verification. The table stores its own copy of the text
 * so a per-item rebuild is a simple DELETE-by-docid + INSERT, idempotent on re-index. §2.5.
 *
 * FTS4 (not FTS5) for OEM compatibility — see FinderDatabase.CREATE_FTS. FTS4 has no bm25(), so
 * [matches] returns each match's `matchinfo` statistics and engine-search computes BM25 from them.
 * `docid` is FTS4's rowid alias.
 */
@Dao
@SkipQueryVerification
interface FtsDao {

    /** Remove an item from keyword search, its caption included. */
    @Transaction
    suspend fun deleteRow(itemId: Long) {
        deleteFtsRow(itemId)
        deleteCaption(itemId)
    }

    @Query("DELETE FROM media_fts WHERE docid = :itemId")
    suspend fun deleteFtsRow(itemId: Long)

    @Query("DELETE FROM caption_fts WHERE docid = :itemId")
    suspend fun deleteCaption(itemId: Long)

    @Query("INSERT INTO caption_fts(docid, caption) VALUES(:itemId, :caption)")
    suspend fun insertCaption(itemId: Long, caption: String)

    /** Caption matches with their `matchinfo` statistics, as [matches] gives for the main table. */
    @Query("SELECT docid AS itemId, matchinfo(caption_fts, 'pcnalx') AS stats FROM caption_fts WHERE caption_fts MATCH :ftsQuery")
    suspend fun captionMatches(ftsQuery: String): List<FtsMatch>

    @Query(
        """
        INSERT INTO media_fts(docid, name, tags, ocr, transcript, place, bucket)
        VALUES(:itemId, :name, :tags, :ocr, :transcript, :place, :bucket)
        """
    )
    suspend fun insertRow(
        itemId: Long,
        name: String?,
        tags: String?,
        ocr: String?,
        transcript: String?,
        place: String?,
        bucket: String?,
    )

    @Transaction
    suspend fun clearAll() {
        clearFts()
        clearCaptions()
    }

    @Query("DELETE FROM media_fts")
    suspend fun clearFts()

    @Query("DELETE FROM caption_fts")
    suspend fun clearCaptions()

    /**
     * Every match with its `matchinfo(..., 'pcnalx')` statistics: phrase and column counts, row and
     * average column lengths in tokens, and per phrase and column the hits here, in all rows, and the
     * rows with any. Cheap enough to fetch for every match, so ranking sees all of them.
     */
    @Query("SELECT docid AS itemId, matchinfo(media_fts, 'pcnalx') AS stats FROM media_fts WHERE media_fts MATCH :ftsQuery")
    suspend fun matches(ftsQuery: String): List<FtsMatch>

    /** Highlighted OCR and transcript snippets for chosen matches. */
    @Query(
        """
        SELECT docid AS itemId,
               0.0 AS rank,
               snippet(media_fts, char(57344), char(57345), '…', 2, 7) AS ocrSnippet,
               snippet(media_fts, char(57344), char(57345), '…', 3, 7) AS transcriptSnippet
        FROM media_fts
        WHERE media_fts MATCH :ftsQuery AND docid IN (:itemIds)
        """
    )
    suspend fun snippets(ftsQuery: String, itemIds: List<Long>): List<FtsHit>
}

/** A keyword match and its FTS4 `matchinfo` blob: unsigned 32-bit integers in native byte order. */
data class FtsMatch(val itemId: Long, val stats: ByteArray) {
    override fun equals(other: Any?): Boolean = other is FtsMatch && other.itemId == itemId && other.stats.contentEquals(stats)
    override fun hashCode(): Int = itemId.hashCode() * 31 + stats.contentHashCode()
}

data class FtsHit(
    val itemId: Long,
    val rank: Double,
    val ocrSnippet: String?,
    val transcriptSnippet: String?,
)

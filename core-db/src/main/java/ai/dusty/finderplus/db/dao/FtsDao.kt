package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * Access to the FTS4 virtual table `media_fts`. Room has no FTS-entity annotation for a
 * manually-created table, so it is created by [ai.dusty.finderplus.db.FinderDatabase]'s callback
 * and these queries opt out of compile-time verification. The table stores its own copy of the text
 * so a per-item rebuild is a simple DELETE-by-docid + INSERT, idempotent on re-index. §2.5.
 *
 * FTS4 (not FTS5) for OEM compatibility — see FinderDatabase.CREATE_FTS. FTS4 has no bm25(), so the
 * keyword leg orders by recency (rowid DESC); relevance ranking comes from the vector legs + RRF
 * fusion in engine-search. `docid` is FTS4's rowid alias.
 */
@Dao
@SkipQueryVerification
interface FtsDao {

    @Query("DELETE FROM media_fts WHERE docid = :itemId")
    suspend fun deleteRow(itemId: Long)

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

    /**
     * Keyword leg of search. Returns matching item ids (recency-ordered) with highlighted snippets
     * for OCR (col 2) and transcript (col 3).
     */
    @Query(
        """
        SELECT docid AS itemId,
               0.0 AS rank,
               snippet(media_fts, '[', ']', '…', 2, 10) AS ocrSnippet,
               snippet(media_fts, '[', ']', '…', 3, 10) AS transcriptSnippet
        FROM media_fts
        WHERE media_fts MATCH :ftsQuery
        ORDER BY docid DESC
        LIMIT :limit
        """
    )
    suspend fun search(ftsQuery: String, limit: Int): List<FtsHit>

    @Query("DELETE FROM media_fts")
    suspend fun clearAll()
}

data class FtsHit(
    val itemId: Long,
    val rank: Double,
    val ocrSnippet: String?,
    val transcriptSnippet: String?,
)

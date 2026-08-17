package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.dusty.finderplus.db.entity.DocumentEntity
import ai.dusty.finderplus.db.entity.SegmentEntity
import ai.dusty.finderplus.db.entity.TagEntity

/**
 * Derived text/tag content. All writes are convergent: a pass first clears its own rows for the
 * item, then inserts its full set, so re-running (or resuming) a pass never duplicates. §6.
 */
@Dao
interface ContentDao {

    // ---- tags ----
    @Query("DELETE FROM tag WHERE item_id = :itemId AND source = :source")
    suspend fun clearTags(itemId: Long, source: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Query("SELECT * FROM tag WHERE item_id = :itemId")
    suspend fun tagsForItem(itemId: Long): List<TagEntity>

    /** Every tag from one source, across all items — used to preserve user labels across a reset. */
    @Query("SELECT * FROM tag WHERE source = :source")
    suspend fun tagsBySource(source: Int): List<TagEntity>

    /**
     * All searchable labels for an item, space-joined for the FTS row.
     *
     * Excludes source 8 (SUGGESTED) deliberately: those are guesses the classifier could not stand
     * behind and the user has not confirmed. Letting them into the FTS row is what made low-confidence
     * noise findable as though it were fact.
     */
    @Query("SELECT group_concat(label, ' ') FROM tag WHERE item_id = :itemId AND source != 8")
    suspend fun tagBlob(itemId: Long): String?

    /** Unconfirmed suggestions, most confident first — the review queue's backlog. */
    @Query(
        """
        SELECT * FROM tag WHERE source = 8
        ORDER BY confidence DESC LIMIT :limit
        """
    )
    suspend fun pendingSuggestions(limit: Int): List<TagEntity>

    @Query("SELECT COUNT(*) FROM tag WHERE source = 8")
    suspend fun pendingSuggestionCount(): Int

    @Query("DELETE FROM tag WHERE item_id = :itemId AND source = 8 AND label = :label")
    suspend fun dropSuggestion(itemId: Long, label: String)

    /** Remove one specific tag row — the undo primitive; clearTags would take siblings with it. */
    @Query("DELETE FROM tag WHERE item_id = :itemId AND source = :source AND label = :label")
    suspend fun deleteTagRow(itemId: Long, source: Int, label: String)

    /** Every source's row for this label on this item — explicit removal means all provenances. */
    @Query("DELETE FROM tag WHERE item_id = :itemId AND label = :label")
    suspend fun deleteLabelFromItem(itemId: Long, label: String)

    /** Items that carry [label] from any of [sources] — the population to reconsider after a removal. */
    @Query("SELECT DISTINCT item_id FROM tag WHERE label = :label AND source IN (:sources)")
    suspend fun itemsWithLabel(label: String, sources: List<Int>): List<Long>

    /** How many items still carry [label] as a USER tag — zero means the user has fully retracted it. */
    @Query("SELECT COUNT(DISTINCT item_id) FROM tag WHERE label = :label AND source = 4")
    suspend fun userCountForLabel(label: String): Int

    /** Purge a label's machine-derived rows everywhere (6=LEARNED, 8=SUGGESTED); USER rows never. */
    @Query("DELETE FROM tag WHERE label = :label AND source IN (6, 8)")
    suspend fun purgeMachineLabel(label: String): Int

    /** What the AI judge has applied, grouped — the user's window into assisted labelling. */
    @Query("SELECT label, COUNT(*) AS n FROM tag WHERE source = 5 GROUP BY label ORDER BY n DESC LIMIT :limit")
    suspend fun vlmLabelCounts(limit: Int): List<LabelCount>

    /** Purge one judge-applied label everywhere — reverting a machine decision needs no ceremony. */
    @Query("DELETE FROM tag WHERE label = :label AND source = 5")
    suspend fun purgeVlmLabel(label: String): Int

    /** Every non-empty document body, for corpus term counting. */
    @Query("SELECT text FROM document WHERE text IS NOT NULL AND length(trim(text)) > 0")
    suspend fun allDocumentTexts(): List<String>

    /**
     * Every (item, label) pair from the sources that describe content.
     *
     * OCR keywords are excluded on purpose: they are *derived* from document frequency, so counting them
     * as evidence would feed the statistic back into itself and let a term justify its own retention.
     */
    @Query("SELECT item_id, label FROM tag WHERE source IN (0, 1, 3, 4, 6, 7)")
    suspend fun allLabelPairs(): List<LabelPair>

    // ---- documents ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity): Long

    @Query("SELECT * FROM document WHERE item_id = :itemId AND source = :source")
    suspend fun document(itemId: Long, source: Int): DocumentEntity?

    @Query("SELECT text FROM document WHERE item_id = :itemId AND source = :source")
    suspend fun documentText(itemId: Long, source: Int): String?

    @Query("UPDATE document SET text = :text, lang = :lang WHERE id = :id")
    suspend fun setDocumentText(id: Long, text: String, lang: String?)

    // ---- segments (timestamped A/V text) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("SELECT * FROM segment WHERE item_id = :itemId ORDER BY start_ms")
    suspend fun segmentsForItem(itemId: Long): List<SegmentEntity>

    /** Concatenated transcript text for the FTS `transcript` column. */
    @Query("SELECT group_concat(text, ' ') FROM segment WHERE item_id = :itemId")
    suspend fun transcriptBlob(itemId: Long): String?

    /** Nearest segment to a vector-hit position, so an A/V result can show *where* it matched. */
    @Query(
        "SELECT * FROM segment WHERE item_id = :itemId ORDER BY ABS(start_ms - :aroundMs) LIMIT 1"
    )
    suspend fun segmentNear(itemId: Long, aroundMs: Long): SegmentEntity?
}

/** Projection for corpus label counting. */
data class LabelPair(val item_id: Long, val label: String)

/** A label and how many items carry it. */
data class LabelCount(val label: String, val n: Int)

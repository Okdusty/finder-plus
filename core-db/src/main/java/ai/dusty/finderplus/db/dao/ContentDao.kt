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
     * All searchable labels for an item, space-joined for the FTS row. Excludes unconfirmed
     * suggestions (8 = SUGGESTED, 10 = OBJECT_REVIEW): they must not become searchable until the user
     * confirms them.
     */
    @Query("SELECT group_concat(label, ' ') FROM tag WHERE item_id = :itemId AND source NOT IN (8, 10)")
    suspend fun tagBlob(itemId: Long): String?

    /**
     * Unconfirmed concept guesses (8), most confident first. Detector labels awaiting review (10) are
     * asked through `label_question`, which also records why and the answer.
     */
    @Query(
        """
        SELECT * FROM tag WHERE source = 8
        ORDER BY confidence DESC LIMIT :limit
        """
    )
    suspend fun pendingSuggestions(limit: Int): List<TagEntity>

    @Query("SELECT COUNT(*) FROM tag WHERE source = 8")
    suspend fun pendingSuggestionCount(): Int

    /** Every object name the detector has used: on items (1, 10) or in questions already answered. */
    @Query("SELECT label FROM tag WHERE source IN (1, 10) UNION SELECT label FROM label_question")
    suspend fun objectLabels(): List<String>

    /** Every label in the index, most used first. */
    @Query("SELECT label FROM tag GROUP BY label ORDER BY COUNT(*) DESC LIMIT :limit")
    suspend fun labelsByUse(limit: Int): List<String>

    /** Detector labels, asserted (1) and held for review (10). */
    @Query("SELECT * FROM tag WHERE source IN (1, 10)")
    suspend fun objectTags(): List<TagEntity>

    /** Re-file one label between sources, e.g. from asserted to held. */
    @Query("UPDATE OR IGNORE tag SET source = :to WHERE item_id = :itemId AND label = :label AND source = :from")
    suspend fun moveTag(itemId: Long, label: String, from: Int, to: Int)

    @Query("SELECT * FROM tag WHERE item_id IN (:itemIds) AND label = :label AND source IN (:sources)")
    suspend fun tagRows(itemIds: List<Long>, label: String, sources: List<Int>): List<TagEntity>

    @Query("DELETE FROM tag WHERE item_id = :itemId AND label = :label AND source IN (:sources)")
    suspend fun deleteTagRows(itemId: Long, label: String, sources: List<Int>)

    @Query("DELETE FROM tag WHERE item_id = :itemId AND source IN (8, 10) AND label = :label")
    suspend fun dropSuggestion(itemId: Long, label: String)

    /** Remove one specific tag row (the undo primitive); clearTags would take siblings with it. */
    @Query("DELETE FROM tag WHERE item_id = :itemId AND source = :source AND label = :label")
    suspend fun deleteTagRow(itemId: Long, source: Int, label: String)

    /** Every source's row for this label on this item: an explicit removal covers all provenances. */
    @Query("DELETE FROM tag WHERE item_id = :itemId AND label = :label")
    suspend fun deleteLabelFromItem(itemId: Long, label: String)

    /** Items that carry [label] from any of [sources]: the set to reconsider after a removal. */
    @Query("SELECT DISTINCT item_id FROM tag WHERE label = :label AND source IN (:sources)")
    suspend fun itemsWithLabel(label: String, sources: List<Int>): List<Long>

    /** How many items still carry [label] as a USER tag; zero means the user has fully retracted it. */
    @Query("SELECT COUNT(DISTINCT item_id) FROM tag WHERE label = :label AND source = 4")
    suspend fun userCountForLabel(label: String): Int

    /** Purge a label's machine-derived rows everywhere (6 = LEARNED, 8 = SUGGESTED), never USER rows. */
    @Query("DELETE FROM tag WHERE label = :label AND source IN (6, 8)")
    suspend fun purgeMachineLabel(label: String): Int

    /**
     * The most common content labels (object, user, judge, learned, concept, face), counted by item.
     * Seeds the pop-up's suggestions before there is any search history.
     */
    @Query("SELECT label, COUNT(DISTINCT item_id) AS n FROM tag WHERE source IN (1, 4, 5, 6, 7, 9) GROUP BY label ORDER BY n DESC LIMIT :limit")
    suspend fun topContentLabels(limit: Int): List<LabelCount>

    /** Every item with at least one tag from [source]; the full-audit variant of [itemsWithTag]. */
    @Query("SELECT DISTINCT item_id FROM tag WHERE source = :source ORDER BY item_id")
    suspend fun itemsWithSource(source: Int): List<Long>

    /** Items carrying [label] from [source], for diagnostics that re-examine why a label was given. */
    @Query("SELECT DISTINCT item_id FROM tag WHERE source = :source AND label = :label ORDER BY confidence DESC")
    suspend fun itemsWithTag(source: Int, label: String): List<Long>

    /** Labels the AI judge has applied, with how many times each. */
    @Query("SELECT label, COUNT(*) AS n FROM tag WHERE source = 5 GROUP BY label ORDER BY n DESC LIMIT :limit")
    suspend fun vlmLabelCounts(limit: Int): List<LabelCount>

    /** Purge one judge-applied label everywhere. */
    @Query("DELETE FROM tag WHERE label = :label AND source = 5")
    suspend fun purgeVlmLabel(label: String): Int

    /** Every non-empty document body, for corpus term counting. */
    @Query("SELECT text FROM document WHERE text IS NOT NULL AND length(trim(text)) > 0")
    suspend fun allDocumentTexts(): List<String>

    /**
     * Every (item, label) pair from the sources that describe content. OCR keywords are excluded
     * because they are derived from document frequency; counting them would let a term justify its own
     * retention.
     */
    @Query("SELECT item_id, label FROM tag WHERE source IN (0, 1, 3, 4, 6, 7, 9)")
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

    @Query("UPDATE document SET ts_ms = :tsMs WHERE id = :id")
    suspend fun setDocumentTime(id: Long, tsMs: Long?)

    // ---- segments (timestamped A/V text) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("SELECT * FROM segment WHERE item_id = :itemId ORDER BY start_ms")
    suspend fun segmentsForItem(itemId: Long): List<SegmentEntity>

    /**
     * Concatenated speech for the FTS `transcript` column. Speech only: text read off keyframes is the
     * video's OCR, and counting it as speech would repeat it once per frame it stays on screen.
     */
    @Query("SELECT group_concat(text, ' ') FROM (SELECT text FROM segment WHERE item_id = :itemId AND source_ref = 0 ORDER BY start_ms)")
    suspend fun transcriptBlob(itemId: Long): String?

    /** Spoken segments in time order: what transcript windows are built from. */
    @Query("SELECT * FROM segment WHERE item_id = :itemId AND source_ref = 0 ORDER BY start_ms")
    suspend fun speechSegments(itemId: Long): List<SegmentEntity>

    /** Text read off keyframes, in time order; repeats across frames are collapsed by the caller. */
    @Query("SELECT text FROM segment WHERE item_id = :itemId AND source_ref >= 1 ORDER BY start_ms")
    suspend fun keyframeTexts(itemId: Long): List<String>

    /** Nearest segment to a position, so an A/V result can show where it matched. */
    @Query(
        "SELECT * FROM segment WHERE item_id = :itemId ORDER BY ABS(start_ms - :aroundMs) LIMIT 1"
    )
    suspend fun segmentNear(itemId: Long, aroundMs: Long): SegmentEntity?
}

/** Projection for corpus label counting. */
data class LabelPair(val item_id: Long, val label: String)

/** A label and how many items carry it. */
data class LabelCount(val label: String, val n: Int)

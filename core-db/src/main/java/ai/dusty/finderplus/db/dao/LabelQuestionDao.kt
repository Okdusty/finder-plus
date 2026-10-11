package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ai.dusty.finderplus.db.entity.LabelQuestionEntity

/** Questions about detector labels and the user's answers. See [LabelQuestionEntity]. */
@Dao
interface LabelQuestionDao {

    /** Replace an item's pending questions. Answered rows win on conflict, so a verdict is never reopened. */
    @Transaction
    suspend fun replacePending(itemId: Long, rows: List<LabelQuestionEntity>) {
        clearPending(itemId)
        insertIgnore(rows)
    }

    @Query("DELETE FROM label_question WHERE item_id = :itemId AND verdict IS NULL")
    suspend fun clearPending(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<LabelQuestionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<LabelQuestionEntity>)

    @Query("SELECT * FROM label_question WHERE verdict IS NULL")
    suspend fun pending(): List<LabelQuestionEntity>

    /** Pending questions with at least one reason in [reasonMask]. */
    @Query("SELECT COUNT(*) FROM label_question WHERE verdict IS NULL AND (reasons & :reasonMask) != 0")
    suspend fun pendingCount(reasonMask: Int): Int

    @Query("SELECT * FROM label_question WHERE item_id = :itemId")
    suspend fun forItem(itemId: Long): List<LabelQuestionEntity>

    @Query("SELECT * FROM label_question WHERE item_id IN (:itemIds) AND label = :label")
    suspend fun forItems(itemIds: List<Long>, label: String): List<LabelQuestionEntity>

    /** Answered questions for [labels], the evidence each label's confidence cut is learned from. */
    @Query("SELECT * FROM label_question WHERE verdict IS NOT NULL AND label IN (:labels)")
    suspend fun answeredFor(labels: List<String>): List<LabelQuestionEntity>

    @Query("SELECT * FROM label_question WHERE verdict IS NOT NULL")
    suspend fun answered(): List<LabelQuestionEntity>

    @Query("UPDATE label_question SET verdict = :verdict, updated_at = :now WHERE item_id = :itemId AND label = :label")
    suspend fun answer(itemId: Long, label: String, verdict: Int, now: Long)

    @Query("DELETE FROM label_question WHERE item_id = :itemId AND label = :label")
    suspend fun delete(itemId: Long, label: String)

    /** Pending questions belong to the index that raised them; answers are kept. */
    @Query("DELETE FROM label_question WHERE verdict IS NULL")
    suspend fun clearAllPending()

    @Query("DELETE FROM label_question")
    suspend fun clearAll()
}

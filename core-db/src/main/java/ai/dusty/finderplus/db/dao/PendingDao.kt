package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.dusty.finderplus.db.entity.DiagEventEntity
import ai.dusty.finderplus.db.entity.PendingLabelEntity

@Dao
interface PendingLabelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(edit: PendingLabelEntity)

    @Query("DELETE FROM pending_label WHERE item_id = :itemId AND label = :label")
    suspend fun clear(itemId: Long, label: String)

    @Query("SELECT * FROM pending_label WHERE item_id = :itemId")
    suspend fun forItem(itemId: Long): List<PendingLabelEntity>

    @Query("SELECT * FROM pending_label ORDER BY created_at")
    suspend fun all(): List<PendingLabelEntity>

    @Query("SELECT COUNT(*) FROM pending_label")
    suspend fun count(): Int

    @Query("DELETE FROM pending_label")
    suspend fun clearAll()
}

@Dao
interface DiagDao {
    @Insert
    suspend fun insert(events: List<DiagEventEntity>)

    @Query("SELECT * FROM diag_event WHERE kind = :kind AND at >= :since ORDER BY at DESC LIMIT :limit")
    suspend fun recent(kind: String, since: Long, limit: Int): List<DiagEventEntity>

    @Query("SELECT * FROM diag_event ORDER BY at")
    suspend fun all(): List<DiagEventEntity>

    /** Keeps the newest [keep] events. */
    @Query("DELETE FROM diag_event WHERE id NOT IN (SELECT id FROM diag_event ORDER BY at DESC LIMIT :keep)")
    suspend fun prune(keep: Int)

    @Query("DELETE FROM diag_event")
    suspend fun clearAll()
}

package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ai.dusty.finderplus.db.entity.NameRankingEntity

@Dao
interface NameRankingDao {

    /** Replace an item's ranking with [rows]. */
    @Transaction
    suspend fun replaceForItem(itemId: Long, rows: List<NameRankingEntity>) {
        clear(itemId)
        insert(rows)
    }

    @Query("DELETE FROM name_ranking WHERE item_id = :itemId")
    suspend fun clear(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<NameRankingEntity>)

    @Query("SELECT * FROM name_ranking WHERE item_id = :itemId")
    suspend fun forItem(itemId: Long): List<NameRankingEntity>

    @Query("SELECT * FROM name_ranking ORDER BY created_at")
    suspend fun all(): List<NameRankingEntity>

    @Query("DELETE FROM name_ranking")
    suspend fun clearAll()
}

package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ai.dusty.finderplus.db.entity.ObjectRegionEntity

@Dao
interface ObjectRegionDao {

    /** Replace an item's regions with those of its latest description. */
    @Transaction
    suspend fun replaceForItem(itemId: Long, rows: List<ObjectRegionEntity>) {
        clear(itemId)
        insert(rows)
    }

    @Query("DELETE FROM object_region WHERE item_id = :itemId")
    suspend fun clear(itemId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rows: List<ObjectRegionEntity>)

    @Query("SELECT * FROM object_region WHERE label = :label")
    suspend fun forLabel(label: String): List<ObjectRegionEntity>

    @Query("SELECT * FROM object_region WHERE label = :label AND item_id IN (:itemIds)")
    suspend fun forItems(label: String, itemIds: List<Long>): List<ObjectRegionEntity>

    /** Each named region of [itemIds] with where in its video it was seen, without the vectors. */
    @Query("SELECT item_id, label, ts_ms FROM object_region WHERE item_id IN (:itemIds)")
    suspend fun momentsFor(itemIds: List<Long>): List<RegionMoment>

    @Query("SELECT ts_ms FROM object_region WHERE item_id = :itemId AND label = :label")
    suspend fun timeOf(itemId: Long, label: String): Long?
}

data class RegionMoment(val item_id: Long, val label: String, val ts_ms: Long?)

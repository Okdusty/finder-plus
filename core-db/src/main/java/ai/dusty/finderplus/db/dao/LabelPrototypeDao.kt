package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.dusty.finderplus.db.entity.LabelPrototypeEntity

@Dao
interface LabelPrototypeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prototype: LabelPrototypeEntity)

    @Query("SELECT * FROM label_prototype WHERE label = :label")
    suspend fun byLabel(label: String): LabelPrototypeEntity?

    /** All prototypes worth matching against; a single-exemplar label is already usable. */
    @Query("SELECT * FROM label_prototype ORDER BY exemplar_count DESC")
    suspend fun all(): List<LabelPrototypeEntity>

    @Query("SELECT COUNT(*) FROM label_prototype")
    suspend fun count(): Int

    @androidx.room.Query("SELECT COUNT(*) FROM label_prototype WHERE origin = :origin")
    suspend fun countByOrigin(origin: Int): Int

    @Query("DELETE FROM label_prototype WHERE label = :label")
    suspend fun delete(label: String)

    @Query("DELETE FROM label_prototype")
    suspend fun deleteAll()
}

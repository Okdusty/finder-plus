package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.dusty.finderplus.db.entity.MediaProfileEntity

/** Access to the consolidated "AI revision" text per item. */
@Dao
interface MediaProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: MediaProfileEntity)

    @Query("SELECT text FROM media_profile WHERE item_id = :itemId")
    suspend fun text(itemId: Long): String?
}

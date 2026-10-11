package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ai.dusty.finderplus.db.entity.IndexRunEntity

@Dao
interface IndexRunDao {

    @Insert
    suspend fun insert(run: IndexRunEntity): Long

    @Query("SELECT * FROM index_run WHERE id = :id")
    suspend fun byId(id: Long): IndexRunEntity?

    @Query("SELECT * FROM index_run ORDER BY id DESC LIMIT 1")
    suspend fun latest(): IndexRunEntity?

    /**
     * The run a new slice should continue: unfinished and not stopped. Reusing it keeps total/done
     * continuous across slices instead of creating a new run row per slice.
     */
    @Query("SELECT * FROM index_run WHERE finished_at IS NULL AND stop_requested = 0 ORDER BY id DESC LIMIT 1")
    suspend fun latestResumable(): IndexRunEntity?

    /** Close out runs abandoned by a killed process so they don't linger as RUNNING forever. */
    @Query("UPDATE index_run SET status = :status, finished_at = :now WHERE finished_at IS NULL AND id != :keepId")
    suspend fun closeAbandoned(keepId: Long, status: Int, now: Long)

    @Query("UPDATE index_run SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: Int)

    /** The durable cooperative-stop flag; the engine also mirrors it in an in-memory AtomicBoolean. */
    @Query("UPDATE index_run SET stop_requested = 1, status = 4 WHERE id = :id")
    suspend fun requestStop(id: Long)

    @Query("SELECT stop_requested FROM index_run WHERE id = :id")
    suspend fun isStopRequested(id: Long): Int

    @Query("UPDATE index_run SET total_units = :total, done_units = :done, failed_units = :failed WHERE id = :id")
    suspend fun setCounts(id: Long, total: Int, done: Int, failed: Int)

    @Query("UPDATE index_run SET status = :status, finished_at = :now WHERE id = :id")
    suspend fun finish(id: Long, status: Int, now: Long)

    @Query("UPDATE index_run SET last_generation = :generation WHERE id = :id")
    suspend fun setGeneration(id: Long, generation: Long)

    @Query("DELETE FROM index_run")
    suspend fun deleteAll()
}

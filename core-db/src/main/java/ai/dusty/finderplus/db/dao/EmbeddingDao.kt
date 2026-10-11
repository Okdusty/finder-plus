package ai.dusty.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.dusty.finderplus.db.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embeddings: List<EmbeddingEntity>)

    /** Raw vectors for a kind, optionally pre-filtered to a candidate item set to shrink the scan. */
    @Query("SELECT item_id, source_ref, vec, ts_ms FROM embedding WHERE kind = :kind")
    suspend fun vectorsOfKind(kind: Int): List<VectorRow>

    @Query("SELECT item_id, source_ref, vec, ts_ms FROM embedding WHERE kind = :kind AND item_id IN (:itemIds)")
    suspend fun vectorsOfKindFiltered(kind: Int, itemIds: List<Long>): List<VectorRow>

    /** Items of [mediaKinds] with vectors of [kind] that have no timestamp yet, from indexes made before they were stored. */
    @Query(
        """
        SELECT DISTINCT e.item_id FROM embedding e JOIN media_item m ON m.id = e.item_id
        WHERE e.kind = :kind AND m.kind IN (:mediaKinds) AND e.source_ref >= :minSourceRef AND e.ts_ms IS NULL
        LIMIT :limit
        """
    )
    suspend fun itemsMissingTimes(kind: Int, mediaKinds: List<Int>, minSourceRef: Int, limit: Int): List<Long>

    @Query("SELECT source_ref FROM embedding WHERE item_id = :itemId AND kind = :kind")
    suspend fun sourceRefs(itemId: Long, kind: Int): List<Int>

    @Query("UPDATE embedding SET ts_ms = :tsMs WHERE item_id = :itemId AND kind = :kind AND source_ref = :sourceRef")
    suspend fun setTime(itemId: Long, kind: Int, sourceRef: Int, tsMs: Long)

    @Query("DELETE FROM embedding WHERE item_id = :itemId AND kind = :kind")
    suspend fun clear(itemId: Long, kind: Int)

    @Query("SELECT COUNT(*) FROM embedding WHERE kind = :kind")
    suspend fun countOfKind(kind: Int): Int
}

/** Projection used by the vector store's brute-force cosine scan. */
data class VectorRow(
    val item_id: Long,
    val source_ref: Int,
    val vec: ByteArray,
    val ts_ms: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorRow) return false
        return item_id == other.item_id && source_ref == other.source_ref && vec.contentEquals(other.vec)
    }

    override fun hashCode(): Int {
        var result = item_id.hashCode()
        result = 31 * result + source_ref
        result = 31 * result + vec.contentHashCode()
        return result
    }
}

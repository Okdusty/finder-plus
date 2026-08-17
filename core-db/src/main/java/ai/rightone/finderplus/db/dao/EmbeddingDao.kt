package ai.rightone.finderplus.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ai.rightone.finderplus.db.entity.EmbeddingEntity

@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(embeddings: List<EmbeddingEntity>)

    /** Raw vectors for a kind, optionally pre-filtered to a candidate item set to shrink the scan. */
    @Query("SELECT item_id, source_ref, vec FROM embedding WHERE kind = :kind")
    suspend fun vectorsOfKind(kind: Int): List<VectorRow>

    @Query("SELECT item_id, source_ref, vec FROM embedding WHERE kind = :kind AND item_id IN (:itemIds)")
    suspend fun vectorsOfKindFiltered(kind: Int, itemIds: List<Long>): List<VectorRow>

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

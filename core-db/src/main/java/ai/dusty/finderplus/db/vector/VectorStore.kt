package ai.rightone.finderplus.db.vector

import ai.rightone.finderplus.db.dao.EmbeddingDao
import ai.rightone.finderplus.model.EmbeddingKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single vector match: which item, which sub-item stream, and the cosine score. */
data class VectorHit(val itemId: Long, val sourceRef: Int, val score: Float)

interface VectorStore {
    /**
     * Top-[k] cosine matches for [query] among embeddings of [kind]. [filterItemIds], when given,
     * restricts the scan to a candidate set (e.g. the FTS hits) to keep it cheap at scale.
     * [query] must be L2-normalized; stored vectors are normalized at write time, so cosine is a dot.
     */
    suspend fun search(
        kind: EmbeddingKind,
        query: FloatArray,
        k: Int,
        filterItemIds: LongArray? = null,
    ): List<VectorHit>
}

/**
 * Brute-force cosine over the `float32` blobs. Adequate to ~50k vectors on-device; behind this
 * interface a `sqlite-vec` / HNSW index can be dropped in with no caller change. See docs/design/04-SEARCH.md §4.
 */
class BruteForceVectorStore(private val embeddingDao: EmbeddingDao) : VectorStore {

    override suspend fun search(
        kind: EmbeddingKind,
        query: FloatArray,
        k: Int,
        filterItemIds: LongArray?,
    ): List<VectorHit> {
        val rows = if (filterItemIds != null && filterItemIds.isNotEmpty()) {
            embeddingDao.vectorsOfKindFiltered(kind.ordinal, filterItemIds.toList())
        } else {
            embeddingDao.vectorsOfKind(kind.ordinal)
        }
        if (rows.isEmpty()) return emptyList()

        // Bounded top-k via a small min-heap keyed by score.
        val heap = java.util.PriorityQueue<VectorHit>(k + 1, compareBy { it.score })
        for (row in rows) {
            val v = Vecs.fromBytes(row.vec)
            if (v.size != query.size) continue
            val score = Vecs.dot(query, v)
            if (heap.size < k) {
                heap.add(VectorHit(row.item_id, row.source_ref, score))
            } else if (score > heap.peek()!!.score) {
                heap.poll()
                heap.add(VectorHit(row.item_id, row.source_ref, score))
            }
        }
        return heap.sortedByDescending { it.score }
    }
}

/** Float32 <-> little-endian byte packing, plus normalized-vector helpers. */
object Vecs {

    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = buf.float
        return out
    }

    /** Dot product; equals cosine similarity when both operands are L2-normalized. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun normalized(v: FloatArray): FloatArray {
        var norm = 0f
        for (f in v) norm += f * f
        norm = kotlin.math.sqrt(norm)
        if (norm == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }
}

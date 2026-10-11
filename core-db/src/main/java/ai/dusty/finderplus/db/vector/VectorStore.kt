package ai.dusty.finderplus.db.vector

import ai.dusty.finderplus.db.dao.EmbeddingDao
import ai.dusty.finderplus.model.EmbeddingKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A single vector match: which item, which sub-item stream, the cosine score, and where in the media. */
data class VectorHit(val itemId: Long, val sourceRef: Int, val score: Float, val tsMs: Long? = null)

/**
 * How one item's vectors scored against a query: its best and mean cosine, how many it has, and where
 * in the media its best vector was taken.
 */
data class ItemScore(
    val itemId: Long,
    val best: Float,
    val bestSourceRef: Int,
    val mean: Float,
    val count: Int,
    val bestTsMs: Long? = null,
)

/** Every item's [ItemScore], with the mean and spread of all vector scores for the same query. */
data class ItemScores(val items: List<ItemScore>, val mean: Float, val sd: Float)

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

    /**
     * Every item's score summary for [query], for rankers that must judge an item by more than its
     * single best vector. [query] must be L2-normalized. With [nested], vectors of another length are
     * compared at the shorter of the two lengths, which is valid for Matryoshka-trained vectors only.
     */
    suspend fun scoreItems(kind: EmbeddingKind, query: FloatArray, nested: Boolean = false): ItemScores
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
                heap.add(VectorHit(row.item_id, row.source_ref, score, row.ts_ms))
            } else if (score > heap.peek()!!.score) {
                heap.poll()
                heap.add(VectorHit(row.item_id, row.source_ref, score, row.ts_ms))
            }
        }
        return heap.sortedByDescending { it.score }
    }

    override suspend fun scoreItems(kind: EmbeddingKind, query: FloatArray, nested: Boolean): ItemScores {
        class Acc(var best: Float, var ref: Int, var ts: Long?, var sum: Double, var n: Int)
        val acc = HashMap<Long, Acc>()
        val shortened = HashMap<Int, FloatArray>()
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (row in embeddingDao.vectorsOfKind(kind.ordinal)) {
            var v = Vecs.fromBytes(row.vec)
            var q = query
            if (v.size != query.size) {
                if (!nested) continue
                // A Matryoshka vector's leading part, renormalized, is the shorter embedding.
                if (v.size > query.size) v = Vecs.normalized(v.copyOf(query.size))
                else q = shortened.getOrPut(v.size) { Vecs.normalized(query.copyOf(v.size)) }
            }
            val s = Vecs.dot(q, v)
            sum += s; sumSq += s.toDouble() * s; n++
            val a = acc.getOrPut(row.item_id) { Acc(s, row.source_ref, row.ts_ms, 0.0, 0) }
            if (s > a.best) { a.best = s; a.ref = row.source_ref; a.ts = row.ts_ms }
            a.sum += s; a.n++
        }
        val mean = if (n == 0) 0.0 else sum / n
        val sd = if (n < 2) 0.0 else kotlin.math.sqrt(maxOf(0.0, sumSq / n - mean * mean))
        return ItemScores(
            acc.map { (id, a) -> ItemScore(id, a.best, a.ref, (a.sum / a.n).toFloat(), a.n, a.ts) },
            mean.toFloat(), sd.toFloat(),
        )
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

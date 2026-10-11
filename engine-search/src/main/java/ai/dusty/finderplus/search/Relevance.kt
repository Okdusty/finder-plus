package ai.dusty.finderplus.search

import ai.dusty.finderplus.settings.SearchSettings
import ai.dusty.finderplus.settings.Settings
import ai.dusty.finderplus.db.vector.ItemScore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Relevance arithmetic for the search legs, kept free of Android and the database so it is unit-tested.
 */
object Relevance {

    /** BM25 term-frequency saturation and length normalization; textbook values unless changed in the settings. */
    val K1: Double get() = Settings[SearchSettings.BM25_K1]
    val B: Double get() = Settings[SearchSettings.BM25_B]

    /**
     * Okapi BM25 from an FTS4 `matchinfo(..., 'pcnalx')` blob, summed over the query's phrases and
     * the table's columns. Length normalization is what keeps a long transcript from outranking a short
     * caption merely by containing more words.
     */
    fun bm25(matchinfo: ByteArray): Double {
        val buf = ByteBuffer.wrap(matchinfo).order(ByteOrder.nativeOrder()).asIntBuffer()
        val v = IntArray(buf.remaining()).also { buf.get(it) }
        fun u(i: Int) = v[i].toLong() and 0xFFFFFFFFL
        val phrases = u(0).toInt(); val cols = u(1).toInt(); val rows = u(2).toDouble()
        val avg = 3; val len = 3 + cols; val hits = 3 + 2 * cols
        var score = 0.0
        for (p in 0 until phrases) for (c in 0 until cols) {
            val base = hits + 3 * (p * cols + c)
            val tf = u(base).toDouble()
            if (tf == 0.0) continue
            val docs = u(base + 2).toDouble()
            val idf = ln((rows - docs + 0.5) / (docs + 0.5) + 1)
            val norm = 1 - B + B * u(len + c) / maxOf(1L, u(avg + c))
            score += idf * tf * (K1 + 1) / (tf + K1 * norm)
        }
        return score
    }

    /**
     * Items whose best vector stands out for the query, most relevant first, judged fairly between
     * items with one vector (a photo) and items with many (a video's keyframes).
     *
     * The best of n vectors beats a single vector by chance alone, so each item's best score is lowered
     * by the lift n vectors give under no relevance. That lift is measured on this query's own scores:
     * for each item, how far its best vector sits above its mean, scaled by sqrt(2 ln n) as the maximum
     * of n draws grows; the median across items, so the few relevant items do not inflate it.
     *
     * An item is kept only when its corrected score, in standard deviations of this query's scores,
     * exceeds sqrt(2 ln M) for M items: roughly where the best of M unrelated items would land by chance.
     * [standout] scales that line and [liftScale] the correction, both 1 as derived.
     */
    fun fairVectorRanking(
        items: List<ItemScore>,
        mean: Float,
        sd: Float,
        limit: Int,
        standout: Double = 1.0,
        liftScale: Double = 1.0,
    ): List<ItemScore> {
        if (items.isEmpty() || sd <= 0f) return emptyList()
        val perUnit = items.filter { it.count >= 2 }
            .map { (it.best - it.mean) / sd / sqrt(2 * ln(it.count.toDouble())) }
            .sorted()
        val lift = (if (perUnit.isEmpty()) 0.0 else perUnit[perUnit.size / 2]) * liftScale
        val floor = sqrt(2 * ln(items.size.coerceAtLeast(2).toDouble())) * standout
        return items.map { it to correctedZ(it, mean, sd, lift) }
            .filter { it.second >= floor }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun correctedZ(it: ItemScore, mean: Float, sd: Float, lift: Double): Double {
        val z = (it.best - mean) / sd.toDouble()
        return if (it.count < 2) z else z - lift * sqrt(2 * ln(it.count.toDouble()))
    }
}

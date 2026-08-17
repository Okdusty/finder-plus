package ai.rightone.finderplus.search

/**
 * Reciprocal Rank Fusion across the search legs. RRF is scale-free, so it robustly combines FTS BM25
 * ranks with cosine ranks without normalizing their different score ranges. A higher fused score is
 * better; the constant [k] damps the contribution of low-ranked items. See docs/design/04-SEARCH.md §5.
 */
interface RankFuser {
    /** @param weights per-leg multipliers; defaults to 1.0 for any leg without one. */
    fun fuse(legs: List<List<Long>>, weights: List<Float> = emptyList()): List<FusedItem>
}

data class FusedItem(val itemId: Long, val score: Float, val agreeingLegs: Int)

class DefaultRankFuser(private val k: Int = 60) : RankFuser {

    override fun fuse(legs: List<List<Long>>, weights: List<Float>): List<FusedItem> {
        val score = HashMap<Long, Float>()
        val agree = HashMap<Long, Int>()
        for ((legIndex, leg) in legs.withIndex()) {
            // Legs are NOT equally trustworthy. A keyword hit means the word is literally present; a
            // vector hit means something was merely nearest, and every vector leg returns its top-N
            // however poor the match. Weighting keeps a plausible-but-wrong neighbour from outranking
            // an exact match, which is the single most noticeable failure in a hybrid ranker.
            val w = weights.getOrElse(legIndex) { 1f }
            leg.forEachIndexed { rank, id ->
                score[id] = (score[id] ?: 0f) + w / (k + rank + 1)
                agree[id] = (agree[id] ?: 0) + 1
            }
        }
        return score.entries
            .sortedByDescending { it.value }
            .map { FusedItem(it.key, it.value, agree[it.key] ?: 1) }
            // Agreement across independent legs is strong evidence: an item found by both keywords and
            // visual similarity is far more likely to be what was meant than one found by either alone.
            .sortedByDescending { it.score * (1f + AGREEMENT_BONUS * (it.agreeingLegs - 1)) }
    }

    private companion object {
        const val AGREEMENT_BONUS = 0.25f
    }
}

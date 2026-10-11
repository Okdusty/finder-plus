package ai.dusty.finderplus.index

import kotlin.math.sqrt

/**
 * Rewards for one ranked group of candidate names, as group-relative policy optimization scores a
 * group of outputs: each against the others for the same input, not against a fixed scale.
 */
object GroupRewards {

    data class Scored(val label: String, val rank: Int?, val reward: Float, val advantage: Float)

    /**
     * [ranked] is the names the user chose, best first, from [candidates]. The k chosen names get
     * rewards from 1 down to 1/k and the rest 0; each advantage is its reward less the group's mean,
     * over the group's standard deviation, or 0 when every name scored the same.
     */
    fun of(candidates: List<String>, ranked: List<String>): List<Scored> {
        val k = ranked.size
        val rewards = candidates.map { c -> ranked.indexOf(c).let { i -> if (i < 0) 0f else (k - i).toFloat() / k } }
        val mean = rewards.average()
        val sd = sqrt(rewards.sumOf { (it - mean) * (it - mean) } / rewards.size.coerceAtLeast(1))
        return candidates.mapIndexed { i, c ->
            val r = rewards[i]
            Scored(c, ranked.indexOf(c).takeIf { it >= 0 }?.plus(1), r, if (sd == 0.0) 0f else ((r - mean) / sd).toFloat())
        }
    }
}

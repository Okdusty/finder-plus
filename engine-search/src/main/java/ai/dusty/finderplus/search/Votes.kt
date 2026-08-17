package ai.dusty.finderplus.search

import kotlin.math.tanh

/**
 * Implicit relevance voting: the ranker learns which result a query *meant* from what the user does,
 * with no visible voting UI at all.
 *
 * Every interaction is already a ballot — copying a result is the strongest possible "this was the
 * right answer for that query"; opening it is a weaker yes; previewing weaker still; and every result
 * that sat *above* the chosen one and was passed over shades negative (the cascade-model reading:
 * the user saw it and rejected it). Scores accumulate per (folded query term, item), clamped at
 * write time, and at search time become a **bounded multiplicative boost** — `1 + GAIN·tanh(votes/SCALE)`
 * — so history can lift an item past near-ties but can never outshout actual relevance, and a
 * downvoted item sinks without ever being censored from results.
 */
object Votes {

    // Ballot weights. Explicit arrows are the loudest signal — the user stopped to say it outright,
    // reddit-style — then copy ≫ open ≫ preview; the skip penalty is deliberately small because
    // "passed over" is the noisiest signal — a grid shows many results at once and not all were
    // truly seen. Only ever cast from human gestures in the search UI: no worker, judge, or AI
    // process writes votes.
    const val UP = 4f
    const val DOWN = -4f
    const val TAP = 2f
    const val OPEN = 1f
    const val PREVIEW = 0.25f
    const val SKIP = -0.25f

    /** Write-time clamps: no item can buy permanent immunity, none can be voted into oblivion. */
    const val MIN_SCORE = -6f
    const val MAX_SCORE = 12f

    /** How many results above the chosen one are treated as seen-and-rejected. */
    const val SKIP_WINDOW = 8

    private const val GAIN = 0.35f
    private const val SCALE = 4f

    /** Bounded boost multiplier for one item's net vote. tanh keeps it in (1-GAIN, 1+GAIN). */
    fun boost(voteSum: Float): Float = 1f + GAIN * tanh(voteSum / SCALE)

    /**
     * Query text -> vote terms. Same folding family as the speller (`ş→s`, `ı→i`, …) so "kopek" and
     * "köpek" pool their history; length-capped so a rambling query doesn't fan one tap into dozens
     * of rows.
     */
    fun terms(raw: String): List<String> = TOKEN.findAll(fold(raw.lowercase()))
        .map { it.value }
        .filter { it.length >= 2 }
        .distinct()
        .take(6)
        .toList()

    /**
     * Re-rank an ordered result list by voted score. Rank is turned back into a score (RRF-style
     * `1/(rank+k)`) before boosting, so a #1 with no votes still comfortably beats a #40 with many —
     * votes tune the order, they do not replace relevance.
     */
    fun rerank(order: List<Long>, votes: Map<Long, Float>): List<Long> {
        if (votes.isEmpty()) return order
        return order.mapIndexed { rank, id -> id to (1f / (rank + K)) * boost(votes[id] ?: 0f) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private const val K = 10
    private val TOKEN = Regex("""\p{L}{2,}""")

    private fun fold(s: String): String = buildString(s.length) {
        for (c in s) append(FOLD[c] ?: c)
    }

    private val FOLD = mapOf(
        'ı' to 'i', 'İ' to 'i', 'ş' to 's', 'ç' to 'c', 'ğ' to 'g', 'ö' to 'o', 'ü' to 'u',
        'â' to 'a', 'î' to 'i', 'û' to 'u', 'é' to 'e', 'è' to 'e', 'ä' to 'a', 'ß' to 's',
    )
}

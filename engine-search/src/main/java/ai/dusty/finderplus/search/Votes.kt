package ai.dusty.finderplus.search

import ai.dusty.finderplus.settings.LearningSettings
import ai.dusty.finderplus.settings.Settings
import kotlin.math.tanh

/**
 * Implicit relevance voting: the ranker learns which result a query *meant* from what the user does,
 * with no visible voting UI at all.
 *
 * Every interaction is already a ballot — copying a result is the strongest possible "this was the
 * right answer for that query"; opening it is a weaker yes; previewing weaker still; and every result
 * that sat *above* the chosen one and was passed over shades negative (the cascade-model reading:
 * the user saw it and rejected it). Scores accumulate per (query as typed, item), clamped at write
 * time, and at search time become a **bounded multiplicative boost** — `1 + GAIN·tanh(votes/SCALE)` —
 * so history can lift an item past near-ties but can never outshout actual relevance, and a downvoted
 * item sinks without ever being censored from results.
 *
 * Keyed by the whole query ([queryKey]), not by its words: a pick for a query of several words says
 * what that query meant, not what each word means alone, and per-word history spread one pick onto
 * every later search sharing a word with it, words that are often labels too.
 */
object Votes {

    // Ballot weights. Explicit arrows are the loudest signal — the user stopped to say it outright,
    // reddit-style — then copy ≫ open ≫ preview; the skip penalty is deliberately small because
    // "passed over" is the noisiest signal — a grid shows many results at once and not all were
    // truly seen. Only ever cast from human gestures in the search UI: no worker, judge, or AI
    // process writes votes. The defaults are in LearningSettings, where the user can change them.
    val UP: Float get() = Settings[LearningSettings.REWARD_UP].toFloat()
    val DOWN: Float get() = Settings[LearningSettings.REWARD_DOWN].toFloat()
    val TAP: Float get() = Settings[LearningSettings.REWARD_COPY].toFloat()
    val OPEN: Float get() = Settings[LearningSettings.REWARD_OPEN].toFloat()
    val PREVIEW: Float get() = Settings[LearningSettings.REWARD_PREVIEW].toFloat()
    val SKIP: Float get() = Settings[LearningSettings.REWARD_SKIP].toFloat()

    /** Write-time clamps: no item can buy permanent immunity, none can be voted into oblivion. */
    const val MIN_SCORE = -6f
    const val MAX_SCORE = 12f

    /** How many results above the chosen one are treated as seen-and-rejected. */
    const val SKIP_WINDOW = 8

    private val GAIN: Float get() = Settings[LearningSettings.PICK_INFLUENCE].toFloat()
    private const val SCALE = 4f

    /** Bounded boost multiplier for one item's net vote. tanh keeps it in (1-GAIN, 1+GAIN). */
    fun boost(voteSum: Float): Float = 1f + GAIN * tanh(voteSum / SCALE)

    /**
     * Query text -> vote terms. Same folding family as the speller (`ş→s`, `ı→i`, …) so accented and
     * unaccented spellings pool their history; length-capped so a rambling query doesn't fan one tap
     * into dozens of rows.
     */
    fun terms(raw: String): List<String> = TOKEN.findAll(fold(raw.lowercase()))
        .map { it.value }
        .filter { it.length >= 2 }
        .distinct()
        .take(6)
        .toList()

    /**
     * The query a vote belongs to: its [terms], sorted, so case, accents and word order do not split one
     * query's history, while a different set of words is a different query.
     */
    fun queryKey(raw: String): String = terms(raw).sorted().joinToString(" ")

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

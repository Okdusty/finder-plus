package ai.dusty.finderplus.search

/**
 * Orders results by a vision model's scores. Only the checked results move, among the places checked
 * results held; a result that could not be checked, such as a recording, keeps its place. Kept free
 * of Android so it is unit-tested.
 */
object DeepRank {

    /** The first number in [reply], read on a scale of 0 to 10, as 0..1; null when there is none. */
    fun parseScore(reply: String?): Float? {
        val m = reply?.let { NUMBER.find(it) } ?: return null
        return (m.value.replace(',', '.').toFloatOrNull() ?: return null).coerceIn(0f, 10f) / 10f
    }

    /**
     * [ids] reordered by [scores] (0..1, by item id). Items scored below [dropBelow] are left out;
     * among equal scores the earlier one stays first.
     */
    fun order(ids: List<Long>, scores: Map<Long, Float>, dropBelow: Float? = null): List<Long> {
        val kept = ids.filter { id -> dropBelow == null || (scores[id] ?: 1f) >= dropBelow }
        val slots = kept.indices.filter { kept[it] in scores }
        val ranked = slots.map { kept[it] }.sortedByDescending { scores.getValue(it) }
        val out = kept.toMutableList()
        slots.forEachIndexed { i, slot -> out[slot] = ranked[i] }
        return out
    }

    private val NUMBER = Regex("""\d+(?:[.,]\d+)?""")
}

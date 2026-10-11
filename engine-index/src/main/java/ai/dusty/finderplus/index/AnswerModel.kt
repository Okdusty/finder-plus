package ai.dusty.finderplus.index

/**
 * What the user's answers on one object name say about another detection: how they answered for the
 * most similar detected regions they have already answered (CLIP cosine, [K] nearest). Regions, not
 * whole photos: a whole-photo vector describes the scene, and on real answers it did not separate them. Each answer weighs
 * 1 / (number of answers of its kind), so a name answered mostly one way still lets the other way win
 * where it is the closer one.
 *
 * Nearest neighbours rather than a fitted model: with a handful of answers per name there is nothing
 * to fit reliably, and features built from class prototypes leak each answer into its own features,
 * which made held-out checks pass on shuffled answers. With no fitting there is no leak, so the
 * checks below are honest.
 *
 * Only returned when the answers are shown to be separable in CLIP space: the leave-one-out balanced
 * accuracy must reach [MIN_LOO_ACCURACY] and beat every one of [PERMUTATIONS] runs on shuffled answers
 * (p ≤ 0.05). Pure arithmetic over unit vectors, so it is unit-tested.
 */
class AnswerModel private constructor(
    private val yes: List<FloatArray>,
    private val no: List<FloatArray>,
    /** Mean of the yes and no answers' leave-one-out hit rates; 0.5 is no better than chance. */
    val looAccuracy: Float,
) {
    /** Probability that the user would answer yes for a vector [v]. */
    fun probability(v: FloatArray): Float = vote(v, yes, no)

    companion object {
        /** Neighbours that vote. Odd, so a balanced vote cannot tie on count alone. */
        val K: Int get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.NEIGHBOURS].toInt()

        /** Answers of each kind a name needs; with fewer, even a real difference rarely passes the test. */
        val MIN_PER_ANSWER: Int get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.MIN_ANSWERS].toInt()

        /** Balanced leave-one-out accuracy below which the answers are treated as not separable by CLIP. */
        val MIN_LOO_ACCURACY: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.MIN_ACCURACY].toFloat()

        /** Shuffled runs the real one must beat; the default of 19 makes the false-pass rate 1 in 20. */
        val PERMUTATIONS: Int get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.SHUFFLES].toInt()

        /** Above this the predicted yes is trusted enough that the question is not asked. */
        val CONFIDENT_YES: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.CONFIDENT_YES].toFloat()

        /** Most recent answers a label uses; older ones matter less as the index changes. */
        const val MAX_ANSWERS = 200

        fun fit(yes: List<FloatArray>, no: List<FloatArray>): AnswerModel? {
            if (yes.size < MIN_PER_ANSWER || no.size < MIN_PER_ANSWER) return null
            val dim = yes[0].size
            if ((yes + no).any { it.size != dim }) return null
            val loo = leaveOneOut(yes, no)
            if (loo < MIN_LOO_ACCURACY) return null
            // Seeded by the data, so the same answers always give the same verdict.
            val rnd = kotlin.random.Random(yes.size * 7919 + no.size)
            val all = yes + no
            repeat(PERMUTATIONS) {
                val shuffled = all.shuffled(rnd)
                if (leaveOneOut(shuffled.take(yes.size), shuffled.drop(yes.size)) >= loo) return null
            }
            return AnswerModel(yes, no, loo)
        }

        /** Mean of the yes and no hit rates when each answer is predicted from the others. */
        private fun leaveOneOut(yes: List<FloatArray>, no: List<FloatArray>): Float {
            val yesHits = yes.indices.count { i -> vote(yes[i], yes.filterIndexed { j, _ -> j != i }, no) >= 0.5f }
            val noHits = no.indices.count { i -> vote(no[i], yes, no.filterIndexed { j, _ -> j != i }) < 0.5f }
            return (yesHits.toFloat() / yes.size + noHits.toFloat() / no.size) / 2
        }

        private fun vote(v: FloatArray, yes: List<FloatArray>, no: List<FloatArray>): Float {
            if (yes.isEmpty() || no.isEmpty()) return if (yes.isEmpty()) 0f else 1f
            val scored = yes.map { dot(v, it) to (1.0 / yes.size) } + no.map { dot(v, it) to -(1.0 / no.size) }
            var y = 0.0; var n = 0.0
            for ((_, w) in scored.sortedByDescending { it.first }.take(K)) if (w > 0) y += w else n -= w
            return (y / (y + n)).toFloat()
        }

        private fun dot(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
    }
}

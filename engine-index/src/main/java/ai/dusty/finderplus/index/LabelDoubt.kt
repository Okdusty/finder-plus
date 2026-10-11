package ai.dusty.finderplus.index

import kotlin.math.abs

/**
 * Why a detector label is put to the user. Persisted as a bitmask in `label_question.reasons`, so
 * entries are append-only.
 */
enum class Doubt {
    /** The box is the whole image, where the detector cannot answer "none of my classes". */
    WHOLE_FRAME,

    /**
     * Confidence under the label's cut: the default, or the cut learned from the user's answers. Asked
     * about only when the user opts in (see QuestionPrefs); always recorded.
     */
    LOW_CONFIDENCE,

    /** CLIP, reading the same region, ranks other object names above this one. */
    DISAGREE,

    /** The photo's CLIP vector is unusually far from the other photos carrying this label. */
    OUTLIER,

    /** A model fitted to the user's answers on this label predicts they would say no (see [AnswerModel]). */
    ANSWERS;

    val bit: Int get() = 1 shl ordinal

    companion object {
        fun of(mask: Int): Set<Doubt> = entries.filterTo(LinkedHashSet()) { mask and it.bit != 0 }
        fun mask(doubts: Collection<Doubt>): Int = doubts.fold(0) { m, d -> m or d.bit }

        /** The reasons a question needs at least one of to be shown; low confidence only when asked for. */
        fun shown(lowConfidence: Boolean): Int = mask(entries.filter { lowConfidence || it != LOW_CONFIDENCE })

        /**
         * Evidence against the label, as opposed to a lack of evidence for it. A label with any of these
         * is held out of search until answered; one that is only low-confidence stays searchable while asked.
         */
        val CONTRADICTING: Set<Doubt> = setOf(WHOLE_FRAME, DISAGREE, OUTLIER, ANSWERS)
    }
}

/** The arithmetic behind each [Doubt], kept free of Android and the database so it is unit-tested. */
object LabelDoubt {

    /** The lowest cut a label can learn: the describe model reports every object it names, however unsure. */
    const val ASSERT_FLOOR = 0f

    /**
     * Default cut below which a label is asked about, used until the label has enough answers to learn
     * its own. Defaults to the describe model's lowest quartile of object confidence.
     */
    val LOW_CONFIDENCE_CEILING: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.UNCERTAIN_BELOW].toFloat()

    /** Answers a label needs before its cut is learned rather than defaulted. */
    const val MIN_ANSWERS = 6

    /** A cut above every possible confidence: all of the label's detections are asked about. */
    const val ASK_ALWAYS = 1.01f

    /**
     * How far down CLIP's ranking of the object names in the index the detector's label may fall before
     * the two models count as disagreeing. More than 1 because the names include near-synonyms that CLIP
     * legitimately ranks interchangeably.
     */
    val DISAGREE_RANK: Int get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.DISAGREE_RANK].toInt()

    /** Crops smaller than this on either side carry too few pixels for CLIP to read. */
    const val MIN_CROP_EDGE = 32

    /** Photos a label needs before its spread is measured; fewer cannot define "unlike the others". */
    val OUTLIER_MIN_SUPPORT: Int get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.OUTLIER_SUPPORT].toInt()

    /** Robust z-score beyond which a photo is an outlier from its label (median/MAD, Leys et al. 2013). */
    val OUTLIER_Z: Float get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.OUTLIER_Z].toFloat()

    data class Answer(val confidence: Float, val accepted: Boolean)

    /**
     * The confidence below which this label's detections are asked about.
     *
     * Picks the cut that best separates the user's yes answers (above) from their no answers (below);
     * ties go to the higher cut, since an extra question costs less than a wrong label.
     */
    fun learnedCeiling(answers: List<Answer>): Float {
        if (answers.size < MIN_ANSWERS) return LOW_CONFIDENCE_CEILING
        val cuts = (answers.map { it.confidence } + ASSERT_FLOOR + ASK_ALWAYS).filter { it >= ASSERT_FLOOR }.distinct().sorted()
        var best = ASSERT_FLOOR
        var bestErrors = Int.MAX_VALUE
        for (cut in cuts) {
            val errors = answers.count { it.accepted && it.confidence < cut } + answers.count { !it.accepted && it.confidence >= cut }
            if (errors <= bestErrors) { bestErrors = errors; best = cut }
        }
        return best
    }

    /** 0-based position of [index] when [scores] are sorted high to low. */
    fun rank(scores: FloatArray, index: Int): Int = scores.count { it > scores[index] }

    /** Indices whose value is unusually low for this sample: robust z below -[z]. */
    fun lowOutliers(values: FloatArray, z: Float = OUTLIER_Z): Set<Int> {
        if (values.size < OUTLIER_MIN_SUPPORT) return emptySet()
        val med = median(values)
        // 1.4826 scales the MAD to a standard deviation for normally distributed data.
        val spread = median(FloatArray(values.size) { abs(values[it] - med) }) * 1.4826f
        if (spread <= 1e-6f) return emptySet()
        return values.indices.filterTo(LinkedHashSet()) { (med - values[it]) / spread > z }
    }

    /**
     * The crop CLIP is shown for a box: the box plus [margin] of its size on each side, clamped to
     * the image. Null when the result is too small to read.
     */
    fun cropRect(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int, margin: Float = 0.10f): IntArray? {
        val mx = ((right - left) * margin).toInt()
        val my = ((bottom - top) * margin).toInt()
        val l = (left - mx).coerceIn(0, width); val t = (top - my).coerceIn(0, height)
        val r = (right + mx).coerceIn(0, width); val b = (bottom + my).coerceIn(0, height)
        return if (r - l < MIN_CROP_EDGE || b - t < MIN_CROP_EDGE) null else intArrayOf(l, t, r, b)
    }

    private fun median(v: FloatArray): Float {
        val s = v.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }
}

package ai.dusty.finderplus.index

import ai.dusty.finderplus.index.LabelDoubt.Answer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabelDoubtTest {

    @Test fun reasonsRoundTripThroughTheMask() {
        val set = setOf(Doubt.LOW_CONFIDENCE, Doubt.OUTLIER)
        assertThat(Doubt.of(Doubt.mask(set))).containsExactlyElementsIn(set)
        assertThat(Doubt.of(0)).isEmpty()
    }

    @Test fun lowConfidenceAloneIsShownOnlyWhenAskedFor() {
        val lowOnly = Doubt.mask(listOf(Doubt.LOW_CONFIDENCE))
        val lowAndOutlier = Doubt.mask(listOf(Doubt.LOW_CONFIDENCE, Doubt.OUTLIER))
        assertThat(lowOnly and Doubt.shown(lowConfidence = false)).isEqualTo(0)
        assertThat(lowOnly and Doubt.shown(lowConfidence = true)).isNotEqualTo(0)
        assertThat(lowAndOutlier and Doubt.shown(lowConfidence = false)).isNotEqualTo(0)
    }

    @Test fun tooFewAnswersKeepTheDefaultCut() {
        val answers = List(LabelDoubt.MIN_ANSWERS - 1) { Answer(0.9f, accepted = false) }
        assertThat(LabelDoubt.learnedCeiling(answers)).isEqualTo(LabelDoubt.LOW_CONFIDENCE_CEILING)
    }

    @Test fun cutSeparatesYesFromNo() {
        val answers = listOf(0.45f, 0.48f, 0.52f).map { Answer(it, accepted = false) } +
            listOf(0.71f, 0.80f, 0.92f).map { Answer(it, accepted = true) }
        val cut = LabelDoubt.learnedCeiling(answers)
        assertThat(cut).isGreaterThan(0.52f)
        assertThat(cut).isAtMost(0.71f)
    }

    @Test fun onlyYesAnswersStillAskBelowTheLowestConfirmed() {
        val answers = listOf(0.55f, 0.62f, 0.70f, 0.75f, 0.81f, 0.90f).map { Answer(it, accepted = true) }
        assertThat(LabelDoubt.learnedCeiling(answers)).isEqualTo(0.55f)
    }

    @Test fun onlyNoAnswersAskAboutEverything() {
        val answers = listOf(0.45f, 0.60f, 0.70f, 0.85f, 0.90f, 0.95f).map { Answer(it, accepted = false) }
        assertThat(LabelDoubt.learnedCeiling(answers)).isEqualTo(LabelDoubt.ASK_ALWAYS)
    }

    @Test fun rankCountsStrictlyHigherScores() {
        val scores = floatArrayOf(0.2f, 0.9f, 0.5f, 0.5f)
        assertThat(LabelDoubt.rank(scores, 1)).isEqualTo(0)
        assertThat(LabelDoubt.rank(scores, 2)).isEqualTo(1)
        assertThat(LabelDoubt.rank(scores, 0)).isEqualTo(3)
    }

    @Test fun outliersAreFarBelowTheRest() {
        val values = floatArrayOf(0.80f, 0.82f, 0.79f, 0.81f, 0.83f, 0.78f, 0.80f, 0.82f, 0.41f)
        assertThat(LabelDoubt.lowOutliers(values)).containsExactly(8)
    }

    @Test fun highValuesAreNeverOutliers() {
        val values = floatArrayOf(0.60f, 0.62f, 0.59f, 0.61f, 0.63f, 0.58f, 0.60f, 0.62f, 0.99f)
        assertThat(LabelDoubt.lowOutliers(values)).isEmpty()
    }

    @Test fun smallSamplesHaveNoOutliers() {
        val values = FloatArray(LabelDoubt.OUTLIER_MIN_SUPPORT - 1) { if (it == 0) 0.1f else 0.8f }
        assertThat(LabelDoubt.lowOutliers(values)).isEmpty()
    }

    @Test fun cropIsPaddedAndClamped() {
        assertThat(LabelDoubt.cropRect(0, 0, 100, 100, 120, 120)).isEqualTo(intArrayOf(0, 0, 110, 110))
        assertThat(LabelDoubt.cropRect(10, 10, 30, 30, 120, 120)).isNull()
    }
}

package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Test

class AnswerModelTest {

    private val rnd = Random(7)

    /** A unit vector near [center]: the center plus small noise, as CLIP vectors of similar photos are. */
    private fun near(center: FloatArray, noise: Float = 0.15f): FloatArray {
        val v = FloatArray(center.size) { center[it] + (rnd.nextFloat() - 0.5f) * 2 * noise }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun direction(seed: Int): FloatArray {
        val r = Random(seed)
        return near(FloatArray(32) { r.nextFloat() - 0.5f }, 0f)
    }

    @Test fun separableAnswersGiveAConfidentModel() {
        val a = direction(1); val b = direction(2)
        val m = AnswerModel.fit(List(8) { near(a) }, List(8) { near(b) })
        assertThat(m).isNotNull()
        assertThat(m!!.looAccuracy).isAtLeast(AnswerModel.MIN_LOO_ACCURACY)
        assertThat(m.probability(near(a))).isGreaterThan(0.5f)
        assertThat(m.probability(near(b))).isLessThan(0.5f)
    }

    @Test fun tooFewAnswersOfEitherKindGiveNoModel() {
        val a = direction(1); val b = direction(2)
        assertThat(AnswerModel.fit(List(AnswerModel.MIN_PER_ANSWER - 1) { near(a) }, List(6) { near(b) })).isNull()
        assertThat(AnswerModel.fit(List(6) { near(a) }, List(AnswerModel.MIN_PER_ANSWER - 1) { near(b) })).isNull()
    }

    @Test fun aModelThatOnlyLearnsTheMajorityAnswerIsNotTrusted() {
        // Mostly no, and nothing in the vectors tells yes from no: always saying no is 80% accurate,
        // which plain accuracy would accept.
        val a = direction(4)
        assertThat(AnswerModel.fit(List(5) { near(a, 0.4f) }, List(15) { near(a, 0.4f) })).isNull()
    }

    @Test fun answersCLIPCannotSeparateGiveNoModel() {
        // Yes and no drawn from the same cluster: whatever decided them is not visible in the vectors.
        val a = direction(3)
        assertThat(AnswerModel.fit(List(8) { near(a, 0.4f) }, List(8) { near(a, 0.4f) })).isNull()
    }
}

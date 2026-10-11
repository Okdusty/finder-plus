package ai.dusty.finderplus.index

import ai.dusty.finderplus.settings.ModelHardware
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccelerationDecisionTest {
    private val cpu = listOf(100L, 110L, 105L)

    @Test fun aFasterAcceleratorWithTheSameResultsIsKept() {
        val m = AccelerationDecision.Measured(cpu, listOf(50L, 55L, 52L), agreement = 0.999f)
        assertThat(AccelerationDecision.choose(m)).isEqualTo(ModelHardware.NNAPI)
    }

    @Test fun differentResultsKeepTheProcessorHoweverFast() {
        val m = AccelerationDecision.Measured(cpu, listOf(10L, 10L, 10L), agreement = 0.9f)
        assertThat(AccelerationDecision.choose(m)).isEqualTo(ModelHardware.CPU)
    }

    @Test fun aNearTieKeepsTheProcessor() {
        val m = AccelerationDecision.Measured(cpu, listOf(100L, 101L, 99L), agreement = 1f)
        assertThat(AccelerationDecision.choose(m)).isEqualTo(ModelHardware.CPU)
    }

    @Test fun anAcceleratorThatCannotRunTheModelKeepsTheProcessor() {
        assertThat(AccelerationDecision.choose(AccelerationDecision.Measured(cpu, null, null))).isEqualTo(ModelHardware.CPU)
    }
}

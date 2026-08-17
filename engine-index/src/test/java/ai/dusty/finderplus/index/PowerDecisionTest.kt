package ai.rightone.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the power-source semantics.
 *
 * The bug these exist for produced no error and no crash: a phone held at 80% by Samsung's "Protect
 * battery" reports `NOT_CHARGING` while still supplying the whole device, the governor read that as
 * "on battery", and indexing throttled itself for hours on a plugged-in phone.
 */
class PowerDecisionTest {

    /** Plugged in, charge capped by battery protection — the exact reported state. */
    private val protectedAt80 = PowerState(pluggedIn = true, charging = false, levelPct = 80)
    private val activelyCharging = PowerState(pluggedIn = true, charging = true, levelPct = 60)
    private val onBattery = PowerState(pluggedIn = false, charging = false, levelPct = 80)

    @Test fun chargeCappedButPluggedInCountsAsExternalPower() {
        assertThat(protectedAt80.onExternalPower).isTrue()
        assertThat(protectedAt80.charging).isFalse()
    }

    @Test fun chargeCappedThrottlesLikeChargingNotLikeBattery() {
        val capped = PowerDecision.throttleMs(ThermalLevel.NONE, protectedAt80)
        assertThat(capped).isEqualTo(PowerDecision.throttleMs(ThermalLevel.NONE, activelyCharging))
        assertThat(capped).isLessThan(PowerDecision.throttleMs(ThermalLevel.NONE, onBattery))
    }

    @Test fun chargeCappedCoolsDownLikeChargingNotLikeBattery() {
        val capped = PowerDecision.coolDownMs(YieldReason.NONE, ThermalLevel.NONE, protectedAt80)
        assertThat(capped).isEqualTo(PowerDecision.coolDownMs(YieldReason.NONE, ThermalLevel.NONE, activelyCharging))
        assertThat(capped).isLessThan(PowerDecision.coolDownMs(YieldReason.NONE, ThermalLevel.NONE, onBattery))
    }

    @Test fun lowBatteryNeverStopsWorkWhileOnACable() {
        // A charge limiter can hold the level anywhere, including below the low-battery threshold.
        val cappedLow = PowerState(pluggedIn = true, charging = false, levelPct = 5)
        assertThat(PowerDecision.yieldReason(ThermalLevel.NONE, cappedLow)).isEqualTo(YieldReason.NONE)
    }

    @Test fun lowBatteryStillStopsWorkOnBattery() {
        val draining = PowerState(pluggedIn = false, charging = false, levelPct = 10)
        assertThat(PowerDecision.yieldReason(ThermalLevel.NONE, draining)).isEqualTo(YieldReason.LOW_BATTERY)
    }

    @Test fun heatStillStopsWorkEvenOnACable() {
        // External power removes the battery constraint, never the thermal one — but the line moved
        // from SEVERE to CRITICAL, because at SEVERE the SoC is already throttling itself and an
        // app-level pause on top costs wall-clock without buying degrees.
        assertThat(PowerDecision.yieldReason(ThermalLevel.CRITICAL, activelyCharging))
            .isEqualTo(YieldReason.THERMAL)
        assertThat(PowerDecision.yieldReason(ThermalLevel.CRITICAL, protectedAt80))
            .isEqualTo(YieldReason.THERMAL)
    }

    @Test fun heatStillSlowsThingsDownOnACable() {
        // Being plugged in must not flatten the thermal ramp, or the phone cooks. The ramp is now
        // monotonic rather than strictly increasing: NONE and LIGHT both run with no pause at all,
        // and back-off only begins once the SoC reports real pressure.
        val cable = protectedAt80
        val ramp = listOf(ThermalLevel.NONE, ThermalLevel.LIGHT, ThermalLevel.MODERATE,
                          ThermalLevel.SEVERE, ThermalLevel.CRITICAL)
            .map { PowerDecision.throttleMs(it, cable) }
        assertThat(ramp).isInOrder()
        // ...and it must actually rise by the top of the scale, not stay flat at zero.
        assertThat(ramp.last()).isGreaterThan(ramp.first())
        assertThat(PowerDecision.throttleMs(ThermalLevel.MODERATE, cable))
            .isLessThan(PowerDecision.throttleMs(ThermalLevel.SEVERE, cable))
    }

    // ---- Aggressive-on-mains policy -------------------------------------------------------------

    @Test fun onACableThereIsNoPerUnitPauseUntilItGetsHot() {
        // Wall-clock spent sleeping is wall-clock not spent indexing, and on mains it buys nothing.
        assertThat(PowerDecision.throttleMs(ThermalLevel.NONE, activelyCharging)).isEqualTo(0L)
        assertThat(PowerDecision.throttleMs(ThermalLevel.LIGHT, activelyCharging)).isEqualTo(0L)
    }

    @Test fun severeHeatNoLongerStopsTheSliceOnACable() {
        // At SEVERE the SoC has already cut its own clocks; pausing on top mostly wastes time. The
        // hardware is the real governor, so we keep working and let it do its job.
        assertThat(PowerDecision.yieldReason(ThermalLevel.SEVERE, activelyCharging))
            .isEqualTo(YieldReason.NONE)
        assertThat(PowerDecision.yieldReason(ThermalLevel.SEVERE, protectedAt80))
            .isEqualTo(YieldReason.NONE)
    }

    @Test fun criticalHeatStillStopsEverything() {
        // CRITICAL precedes a thermal shutdown. Losing the device mid-slice costs more than the
        // throughput gained, so this is the one line that is not relaxed.
        assertThat(PowerDecision.yieldReason(ThermalLevel.CRITICAL, activelyCharging))
            .isEqualTo(YieldReason.THERMAL)
        assertThat(PowerDecision.yieldReason(ThermalLevel.CRITICAL, onBattery))
            .isEqualTo(YieldReason.THERMAL)
    }

    @Test fun offTheCableTheCautiousPolicyIsUnchanged() {
        // Relaxing the governor must not quietly drain someone running on battery.
        assertThat(PowerDecision.yieldReason(ThermalLevel.SEVERE, onBattery)).isEqualTo(YieldReason.THERMAL)
        assertThat(PowerDecision.throttleMs(ThermalLevel.NONE, onBattery)).isGreaterThan(0L)
        assertThat(PowerDecision.coolDownMs(YieldReason.NONE, ThermalLevel.MODERATE, onBattery))
            .isAtLeast(2 * 60_000L)
    }

    @Test fun coolDownOnACableIsSecondsNotMinutes() {
        assertThat(PowerDecision.coolDownMs(YieldReason.NONE, ThermalLevel.NONE, activelyCharging))
            .isAtMost(2_000L)
        assertThat(PowerDecision.coolDownMs(YieldReason.THERMAL, ThermalLevel.SEVERE, activelyCharging))
            .isAtMost(60_000L)
    }
}

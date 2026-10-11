package ai.dusty.finderplus.index

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

    @Test fun aCableThatCannotKeepUpIsNotExternalPower() {
        // Plugged into a weak port, battery draining: the battery floor must still protect it.
        val weakCable = PowerState(pluggedIn = true, charging = false, levelPct = 10, discharging = true)
        assertThat(weakCable.onExternalPower).isFalse()
        assertThat(PowerDecision.yieldReason(weakCable)).isEqualTo(YieldReason.LOW_BATTERY)
    }

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
        assertThat(PowerDecision.yieldReason(cappedLow)).isEqualTo(YieldReason.NONE)
    }

    @Test fun lowBatteryStillStopsWorkOnBattery() {
        val draining = PowerState(pluggedIn = false, charging = false, levelPct = 10)
        assertThat(PowerDecision.yieldReason(draining)).isEqualTo(YieldReason.LOW_BATTERY)
    }

    // ---- Heat limits ------------------------------------------------------------------------------

    /** The shipped lines, read from the settings' defaults. */
    private val shipped = HeatLimits.fromSettings()

    @Test fun aWarmBatteryStopsWorkWhateverTheSoCSays() {
        val warm = Heat(ThermalLevel.NONE, headroom = 0.3f, batteryC = shipped.batteryStopC)
        assertThat(PowerDecision.tooHot(warm, starting = false, shipped)).isTrue()
    }

    @Test fun severeThrottlingStopsWork() {
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.SEVERE), starting = false, shipped)).isTrue()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.MODERATE), starting = false, shipped)).isFalse()
    }

    @Test fun headroomStopsWorkBeforeThrottlingStarts() {
        val close = Heat(ThermalLevel.LIGHT, headroom = shipped.headroomStop, batteryC = 30f)
        assertThat(PowerDecision.tooHot(close, starting = false, shipped)).isTrue()
    }

    @Test fun aSliceStartsOnlyWellBelowWhereWorkStops() {
        // Between the two lines a running slice continues but a new one waits, so work does not stop
        // and start around a single reading.
        val between = Heat(ThermalLevel.NONE, headroom = 0.8f, batteryC = 38f)
        assertThat(PowerDecision.tooHot(between, starting = false, shipped)).isFalse()
        assertThat(PowerDecision.tooHot(between, starting = true, shipped)).isTrue()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.MODERATE), starting = true, shipped)).isTrue()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE, 0.5f, 34f), starting = true, shipped)).isFalse()
    }

    @Test fun aCoolPhoneAtRestStartsWork() {
        // Measured on a phone at rest and cool: no heat level, battery 33 °C, forecast 0.75. A fixed
        // forecast start line under that kept every batch waiting.
        val atRest = Heat(ThermalLevel.NONE, headroom = 0.75f, batteryC = 33.1f, processorC = 49.1f)
        assertThat(PowerDecision.tooHot(atRest, starting = true, shipped)).isFalse()
    }

    @Test fun thePhonesOwnForecastLinesAreUsedWhenItPublishesThem() {
        val lines = HeatLimits.fromSettings(mapOf(ThermalLevel.MODERATE to 0.85f, ThermalLevel.SEVERE to 0.95f))
        assertThat(lines.headroomStop).isEqualTo(0.95f)
        assertThat(lines.headroomStart).isEqualTo(0.85f)
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE, headroom = 0.86f, batteryC = 30f), starting = true, lines)).isTrue()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE, headroom = 0.84f, batteryC = 30f), starting = true, lines)).isFalse()
    }

    @Test fun withoutThePhonesLinesTheForecastStopsWorkAtSevere() {
        assertThat(shipped.headroomStop).isEqualTo(1.0f)
        assertThat(shipped.headroomStart).isNull()
    }

    @Test fun missingReadingsAreNotTreatedAsHot() {
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE), starting = true, shipped)).isFalse()
    }

    @Test fun aLineSwitchedOffNeverStopsWork() {
        val off = HeatLimits(batteryStopC = null, batteryStartC = 37f, headroomStop = null, headroomStart = 0.7f, statusStop = null)
        val scorching = Heat(ThermalLevel.CRITICAL, headroom = 1.4f, batteryC = 48f)
        assertThat(PowerDecision.tooHot(scorching, starting = false, off)).isFalse()
        assertThat(PowerDecision.tooHot(scorching, starting = true, off)).isFalse()
    }

    @Test fun withNoStartLineASliceStartsUnderTheStopLine() {
        val noStart = shipped.copy(batteryStartC = null)
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE, batteryC = 39f), starting = true, noStart)).isFalse()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE, batteryC = 40f), starting = true, noStart)).isTrue()
    }

    @Test fun theStatusLineFollowsTheChosenLevel() {
        val atLight = shipped.copy(statusStop = ThermalLevel.LIGHT)
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.LIGHT), starting = false, atLight)).isTrue()
        // Nothing lies below LIGHT but NONE, so a slice may start at NONE.
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.NONE), starting = true, atLight)).isFalse()
        val atCritical = shipped.copy(statusStop = ThermalLevel.CRITICAL)
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.SEVERE), starting = false, atCritical)).isFalse()
        assertThat(PowerDecision.tooHot(Heat(ThermalLevel.SEVERE), starting = true, atCritical)).isTrue()
    }

    @Test fun aBatteryFloorOfMinusOneNeverStopsWork() {
        val nearlyEmpty = PowerState(pluggedIn = false, charging = false, levelPct = 3)
        assertThat(PowerDecision.yieldReason(nearlyEmpty, floorPct = null)).isEqualTo(YieldReason.NONE)
    }

    @Test fun fullSpeedStillWaitsForAHotPhoneToCool() {
        assertThat(PowerDecision.unrestrictedCoolDownMs(YieldReason.THERMAL, ThermalLevel.NONE)).isGreaterThan(0L)
        assertThat(PowerDecision.unrestrictedCoolDownMs(YieldReason.BUDGET, ThermalLevel.NONE)).isEqualTo(0L)
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

    @Test fun offTheCableTheCautiousPolicyIsUnchanged() {
        // Relaxing the governor must not quietly drain someone running on battery.
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

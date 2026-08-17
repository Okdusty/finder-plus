package ai.rightone.finderplus.index

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/** Why a work slice ended early — surfaced in the notification/widget so the pause is explainable. */
enum class YieldReason { NONE, BUDGET, THERMAL, LOW_BATTERY }

/**
 * How the device is powered, as it matters to a long background burn.
 *
 * The distinction between the two flags is the whole point of this type. Android's
 * `BatteryManager.isCharging()` answers "is the battery gaining charge", which is **not** the question
 * an indexer should ask — it should ask "is there an external supply, so running hard costs the user
 * nothing".
 */
data class PowerState(
    /** An external supply is attached (AC, USB, wireless or dock). */
    val pluggedIn: Boolean,
    /** The battery is actively gaining charge. */
    val charging: Boolean,
    val levelPct: Int,
) {
    /**
     * True when work can run at full rate without draining the user's battery.
     *
     * Deliberately keyed on [pluggedIn], not [charging]. Samsung's "Protect battery" caps the charge at
     * 80% and then reports `BATTERY_STATUS_NOT_CHARGING` while the cable still supplies the whole
     * device. Treating that as "on battery" throttled indexing for hours on a phone that was plugged in
     * the entire time — the exact opposite of the intended behaviour. The same holds for any charge
     * limiter, and for a battery sitting at 100%.
     */
    val onExternalPower: Boolean get() = pluggedIn
}

/**
 * Battery/thermal governor for indexing. Indexing is a long background burn, so instead of pinning
 * the CPU until Android kills us we run **bounded slices** with a per-unit pause and a cool-down gap
 * between slices, both scaled by thermal status, power source and battery level.
 * See docs/design/07-BATTERY-POLICY.md.
 */
class PowerPolicy(context: Context, private val unrestricted: () -> Boolean = { false }) {

    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val battery = appContext.getSystemService(BatteryManager::class.java)

    /**
     * Read the sticky `ACTION_BATTERY_CHANGED` broadcast.
     *
     * `BatteryManager` exposes no "is a cable attached" property; `EXTRA_PLUGGED` on this sticky intent
     * is the only reliable source. Passing a null receiver is a query, not a registration, so it needs
     * no receiver-export flags.
     */
    private fun batteryIntent(): Intent? = runCatching {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    fun powerState(): PowerState {
        val intent = batteryIntent()
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return PowerState(
            pluggedIn = plugged != 0,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                (battery?.isCharging ?: false),
            levelPct = batteryLevel(),
        )
    }

    /** Battery actively gaining charge. Kept for reporting; policy decisions use [PowerState.onExternalPower]. */
    fun isCharging(): Boolean = powerState().charging

    /** External supply attached, whether or not the battery is taking charge. */
    fun isPluggedIn(): Boolean = powerState().pluggedIn

    fun batteryLevel(): Int =
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100

    fun thermal(): ThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NONE
        val status = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        return when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalLevel.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            status >= PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
            else -> ThermalLevel.NONE
        }
    }

    fun throttleMs(): Long =
        if (unrestricted()) PowerDecision.unrestrictedThrottleMs(thermal())
        else PowerDecision.throttleMs(thermal(), powerState())

    fun yieldReason(): YieldReason =
        if (unrestricted()) PowerDecision.unrestrictedYieldReason(thermal())
        else PowerDecision.yieldReason(thermal(), powerState())

    fun coolDownMs(reason: YieldReason): Long =
        if (unrestricted()) PowerDecision.unrestrictedCoolDownMs(thermal())
        else PowerDecision.coolDownMs(reason, thermal(), powerState())

    companion object {
        const val LOW_BATTERY_PCT = PowerDecision.LOW_BATTERY_PCT

        /**
         * Wall-clock work budget for one slice. Must stay well under WorkManager's 10-minute
         * execution limit — exceeding it is what silently killed earlier runs.
         */
        const val SLICE_BUDGET_MS = 4 * 60_000L
    }
}

/**
 * The governor's decisions as pure functions of (thermal, power).
 *
 * Split out from [PowerPolicy] so they can be unit-tested without a `Context`. That is not academic:
 * the "plugged in but not charging" case silently throttled a whole indexing run, and a bug that costs
 * hours while producing no error is precisely the kind that needs a test pinning it.
 */
internal object PowerDecision {

    const val LOW_BATTERY_PCT = 15

    /**
     * Pause inserted after each completed work unit — the duty-cycle knob that keeps average CPU low
     * enough for the SoC to stay cool and Android's battery watchdog to leave us alone.
     */
    fun throttleMs(thermal: ThermalLevel, power: PowerState): Long {
        val external = power.onExternalPower
        return when (thermal) {
            // The one place we still back off hard: CRITICAL precedes a thermal shutdown, and losing
            // the device mid-slice costs more than the throughput saved.
            ThermalLevel.CRITICAL -> 800L
            // Even here the pause is small. At SEVERE the SoC has *already* cut its own clocks, so an
            // app-level sleep on top mostly buys wall-clock, not degrees — the hardware is the real
            // governor and it cannot be argued with.
            ThermalLevel.SEVERE -> if (external) 50L else 400L
            ThermalLevel.MODERATE -> if (external) 10L else 200L
            ThermalLevel.LIGHT -> if (external) 0L else 90L
            ThermalLevel.NONE -> when {
                // On a cable the battery is not a constraint at all: run flat out.
                external -> 0L
                power.levelPct >= 50 -> 40L
                else -> 80L
            }
        }
    }

    /** End the current slice early: too hot, or too low on battery to keep burning. */
    fun yieldReason(thermal: ThermalLevel, power: PowerState): YieldReason = when {
        // On external power, keep working through SEVERE and stop only at CRITICAL. SEVERE means the
        // platform is already throttling; CRITICAL means it is heading for a shutdown, which would
        // cost the whole slice. Off the cable the old, cautious line still applies.
        thermal == ThermalLevel.CRITICAL -> YieldReason.THERMAL
        thermal == ThermalLevel.SEVERE && !power.onExternalPower -> YieldReason.THERMAL
        // Only a real battery drain should stop the run. On external power the level is not falling
        // (or is being deliberately held), so a low reading is not a reason to stop.
        !power.onExternalPower && power.levelPct <= LOW_BATTERY_PCT -> YieldReason.LOW_BATTERY
        else -> YieldReason.NONE
    }

    // ---- Unrestricted mode -----------------------------------------------------------------------
    //
    // The user has explicitly asked for maximum throughput and accepted the battery and heat cost, so
    // these ignore power source and battery level entirely. CRITICAL is the single exception: it is the
    // state immediately before a thermal shutdown, and losing the process mid-unit costs more work than
    // the pause saves.

    fun unrestrictedThrottleMs(thermal: ThermalLevel): Long =
        if (thermal == ThermalLevel.CRITICAL) 500L else 0L

    fun unrestrictedYieldReason(thermal: ThermalLevel): YieldReason =
        if (thermal == ThermalLevel.CRITICAL) YieldReason.THERMAL else YieldReason.NONE

    fun unrestrictedCoolDownMs(thermal: ThermalLevel): Long =
        if (thermal == ThermalLevel.CRITICAL) 30_000L else 0L

    /** Idle gap before the next slice — the CPU's chance to cool and the battery's to breathe. */
    fun coolDownMs(reason: YieldReason, thermal: ThermalLevel, power: PowerState): Long = when (reason) {
        YieldReason.THERMAL -> if (power.onExternalPower) 60_000L else 5 * 60_000L
        YieldReason.LOW_BATTERY -> 15 * 60_000L
        else -> when (thermal) {
            ThermalLevel.CRITICAL -> 60_000L
            ThermalLevel.SEVERE -> if (power.onExternalPower) 15_000L else 5 * 60_000L
            ThermalLevel.MODERATE -> if (power.onExternalPower) 5_000L else 2 * 60_000L
            ThermalLevel.LIGHT -> if (power.onExternalPower) 2_000L else 60_000L
            ThermalLevel.NONE -> if (power.onExternalPower) 1_000L else 40_000L
        }
    }
}

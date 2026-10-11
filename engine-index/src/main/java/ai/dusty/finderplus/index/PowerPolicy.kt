package ai.dusty.finderplus.index

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class ThermalLevel { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/** Why a work slice ended early — surfaced in the notification/widget so the pause is explainable. */
enum class YieldReason { NONE, BUDGET, THERMAL, LOW_BATTERY, UNPLUGGED, LOW_MEMORY }

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
    /** The battery reports it is draining, cable or not. */
    val discharging: Boolean = false,
) {
    /**
     * True when work can run at full rate without draining the user's battery.
     *
     * Deliberately keyed on [pluggedIn], not [charging]. Samsung's "Protect battery" caps the charge at
     * 80% and then reports `BATTERY_STATUS_NOT_CHARGING` while the cable still supplies the whole
     * device. Treating that as "on battery" throttled indexing for hours on a phone that was plugged in
     * the entire time — the exact opposite of the intended behaviour. The same holds for any charge
     * limiter, and for a battery sitting at 100%.
     *
     * A cable that cannot keep up is not external power: a weak USB port reported as plugged in while
     * the battery drained at 2 A, so the run treated the battery as free and spent it. Such a battery
     * reports DISCHARGING, which a charge limiter does not.
     */
    val onExternalPower: Boolean get() = pluggedIn && !discharging
}

/**
 * The heat lines indexing keeps to; a null line is switched off. A slice stops at a stop line and starts
 * only under the start line, which falls back to the stop line when switched off.
 */
data class HeatLimits(
    val batteryStopC: Float?,
    val batteryStartC: Float?,
    val headroomStop: Float?,
    val headroomStart: Float?,
    val statusStop: ThermalLevel?,
) {
    companion object {
        fun fromSettings(): HeatLimits {
            fun line(a: ai.dusty.finderplus.settings.Amount) =
                ai.dusty.finderplus.settings.Settings.valueOrNull(a)?.toFloat()
            val s = ai.dusty.finderplus.settings.HeatSettings
            val status = ai.dusty.finderplus.settings.Settings[s.STATUS_STOP]
            return HeatLimits(
                batteryStopC = line(s.BATTERY_STOP),
                batteryStartC = line(s.BATTERY_START),
                headroomStop = line(s.HEADROOM_STOP),
                headroomStart = line(s.HEADROOM_START),
                statusStop = if (status == ai.dusty.finderplus.settings.ThermalLine.NEVER) null
                    else ThermalLevel.valueOf(status.name),
            )
        }
    }
}

/** What the device reports about its temperature; null fields were not reported. */
data class Heat(
    val thermal: ThermalLevel,
    /** The platform's forecast of how close it is to severe throttling: 1.0 is the SEVERE point. */
    val headroom: Float? = null,
    /** Battery temperature, °C. */
    val batteryC: Float? = null,
    /** The processor's hottest zone, °C, where the phone lets apps read it. Shown, not used as a limit. */
    val processorC: Float? = null,
)

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
            discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING,
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

    @Volatile private var headroomAt = 0L
    @Volatile private var lastHeadroom: Float? = null

    /**
     * Battery temperature, thermal status, and the platform's thermal headroom where it reports one.
     * Headroom is read at most every [HEADROOM_INTERVAL_MS]: the platform answers NaN when asked much
     * faster than once a second.
     */
    fun heat(): Heat {
        val tenths = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - headroomAt >= HEADROOM_INTERVAL_MS) {
                headroomAt = now
                lastHeadroom = power?.getThermalHeadroom(HEADROOM_FORECAST_S)?.takeIf { !it.isNaN() }
            }
        }
        val processor = ThermalZones.processorC() ?: if (RootThermal.running) RootThermal.processorC() else null
        return Heat(thermal(), lastHeadroom, if (tenths == Int.MIN_VALUE) null else tenths / 10f, processor)
    }

    /** Whether a batch could start now under the heat limits in the settings. */
    fun tooHotToStart(heat: Heat = heat()): Boolean = PowerDecision.tooHot(heat, starting = true, HeatLimits.fromSettings())

    /** Whether running work would stop now under the heat limits in the settings. */
    fun tooHotToContinue(heat: Heat = heat()): Boolean = PowerDecision.tooHot(heat, starting = false, HeatLimits.fromSettings())

    fun throttleMs(): Long =
        if (unrestricted()) PowerDecision.unrestrictedThrottleMs(thermal())
        else PowerDecision.throttleMs(thermal(), powerState())

    /** Memory the system reports free for apps, in MB. */
    fun availableMb(): Long {
        val am = appContext.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        val info = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return info.availMem / (1024 * 1024)
    }

    /**
     * Why work should stop now, if it should. [starting] applies the lower lines that a new slice must
     * be under, so work does not stop and start around a single reading.
     */
    fun yieldReason(starting: Boolean = false): YieldReason {
        val heat = heat()
        if (PowerDecision.tooHot(heat, starting, HeatLimits.fromSettings())) {
            android.util.Log.i("finderPower", "too hot to ${if (starting) "start" else "continue"}: $heat")
            return YieldReason.THERMAL
        }
        val settings = ai.dusty.finderplus.settings.Settings
        val processing = ai.dusty.finderplus.settings.ProcessingSettings
        if (settings[processing.ONLY_CHARGING] && !powerState().onExternalPower) return YieldReason.UNPLUGGED
        settings.valueOrNull(ai.dusty.finderplus.settings.MemorySettings.MIN_FREE_MB)?.let { floor ->
            val free = availableMb()
            if (free < floor) {
                android.util.Log.i("finderPower", "waiting for memory: ${free} MB free, below $floor")
                return YieldReason.LOW_MEMORY
            }
        }
        return if (unrestricted()) YieldReason.NONE
            else PowerDecision.yieldReason(powerState(), settings.valueOrNull(processing.BATTERY_FLOOR)?.toInt())
    }

    fun coolDownMs(reason: YieldReason): Long =
        if (unrestricted()) PowerDecision.unrestrictedCoolDownMs(reason, thermal())
        else PowerDecision.coolDownMs(reason, thermal(), powerState())

    companion object {
        const val LOW_BATTERY_PCT = PowerDecision.LOW_BATTERY_PCT

        /**
         * Wall-clock work budget for one slice. Must stay well under WorkManager's 10-minute
         * execution limit — exceeding it is what silently killed earlier runs.
         */
        const val SLICE_BUDGET_MS = 4 * 60_000L

        /** The slice budget the user chose, within WorkManager's limit. */
        fun sliceBudgetMs(): Long =
            (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.ProcessingSettings.BATCH_MINUTES] * 60_000).toLong()

        private const val HEADROOM_INTERVAL_MS = 2_000L

        /** How far ahead headroom is forecast: about one unit of work, which cannot be interrupted. */
        private const val HEADROOM_FORECAST_S = 10
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

    // ---- Heat limits, in every mode ---------------------------------------------------------------
    //
    // The SoC protects itself by lowering its clocks, but held at that point for hours it keeps the
    // whole phone, and the battery beside it, at that temperature. The battery has no such protection
    // while it charges, and indexing mostly runs on a cable. So these hold even at full speed. The
    // default lines are in HeatSettings: the battery's keeps a run below the 45 °C from which charging
    // rules (JEITA) cut the charge current, since lithium-ion ageing roughly doubles every 10 °C.

    /**
     * Whether it is too hot to work: any one reading over its line is enough. Thermal status covers
     * devices that report no headroom; a slice resumes one status level below where work stops.
     */
    fun tooHot(heat: Heat, starting: Boolean, limits: HeatLimits): Boolean {
        val battery = heat.batteryC
        val headroom = heat.headroom
        val batteryLine = if (starting) limits.batteryStartC ?: limits.batteryStopC else limits.batteryStopC
        val headroomLine = if (starting) limits.headroomStart ?: limits.headroomStop else limits.headroomStop
        val statusLine = limits.statusStop?.let { stop ->
            if (starting) ThermalLevel.entries[maxOf(stop.ordinal - 1, ThermalLevel.LIGHT.ordinal)] else stop
        }
        return (statusLine != null && heat.thermal >= statusLine) ||
            (limits.batteryStopC != null && battery != null && batteryLine != null && battery >= batteryLine) ||
            (limits.headroomStop != null && headroom != null && headroomLine != null && headroom >= headroomLine)
    }

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

    /**
     * End the current slice early for the battery's charge, at [floorPct] (null: never); heat is judged
     * by [tooHot].
     */
    fun yieldReason(power: PowerState, floorPct: Int? = LOW_BATTERY_PCT): YieldReason = when {
        // Only a real battery drain should stop the run. On external power the level is not falling
        // (or is being deliberately held), so a low reading is not a reason to stop.
        floorPct != null && !power.onExternalPower && power.levelPct <= floorPct -> YieldReason.LOW_BATTERY
        else -> YieldReason.NONE
    }

    // ---- Unrestricted mode -----------------------------------------------------------------------
    //
    // The user has asked for maximum throughput and accepted the battery cost, so these ignore power
    // source and battery level and add no pauses of their own. The heat limits above still apply.

    fun unrestrictedThrottleMs(thermal: ThermalLevel): Long =
        if (thermal == ThermalLevel.CRITICAL) 500L else 0L

    fun unrestrictedCoolDownMs(reason: YieldReason, thermal: ThermalLevel): Long = when {
        reason == YieldReason.THERMAL || reason == YieldReason.LOW_MEMORY -> recheckMs()
        thermal == ThermalLevel.CRITICAL -> 30_000L
        else -> 0L
    }

    /** Wait before checking again whether a hot phone has cooled enough to start a slice. */
    fun recheckMs(): Long =
        (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.HeatSettings.RECHECK] * 1000).toLong()

    /** Idle gap before the next slice — the CPU's chance to cool and the battery's to breathe. */
    fun coolDownMs(reason: YieldReason, thermal: ThermalLevel, power: PowerState): Long = when (reason) {
        YieldReason.THERMAL -> if (power.onExternalPower) recheckMs() else 5 * recheckMs()
        YieldReason.LOW_BATTERY -> 15 * 60_000L
        YieldReason.LOW_MEMORY -> recheckMs()
        // The next slice waits for a charger through its WorkManager constraint.
        YieldReason.UNPLUGGED -> 1_000L
        else -> when (thermal) {
            ThermalLevel.CRITICAL -> 60_000L
            ThermalLevel.SEVERE -> if (power.onExternalPower) 15_000L else 5 * 60_000L
            ThermalLevel.MODERATE -> if (power.onExternalPower) 5_000L else 2 * 60_000L
            ThermalLevel.LIGHT -> if (power.onExternalPower) 2_000L else 60_000L
            ThermalLevel.NONE -> if (power.onExternalPower) 1_000L else 40_000L
        }
    }
}

package ai.rightone.finderplus.index

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the indexing threads on the fastest cores the platform currently allows — without root.
 *
 * Android assigns every process a **cpuset**, and a background worker lands in one restricted to the
 * little cores. On this device (Exynos 2400):
 *
 * ```
 * /dev/cpuset/top-app/cpus     0-9   all ten, including the 3.21 GHz Cortex-X4
 * /dev/cpuset/foreground/cpus  0-8
 * /dev/cpuset/moderate/cpus    0-3   little Cortex-A520 only, 1.96 GHz
 * /dev/cpuset/background/cpus  0-3   little only
 * ```
 *
 * An earlier version escaped the cpuset by writing to `/dev/cpuset` via `su`. That path is gone:
 * a deployable app cannot require root, and the unprivileged levers recover most of the win —
 * running as a **foreground service** grants the `foreground` cpuset (cores 0-8, all the big
 * Cortex-A720s), and native `sched_setaffinity` then pins ggml's pool to the fastest cores *within*
 * that set. What is genuinely lost without root is only the single prime core.
 *
 * Affinity must be **re-applied**: the platform reassigns the cpuset whenever process importance
 * changes (observed: `top-app` -> `abnormal` within two minutes of sustained load on Samsung), and a
 * cgroup move clears thread affinity. Callers re-ensure per work slice; the fix is a cheap syscall.
 */
@Singleton
class CpuBooster @Inject constructor() {

    enum class State { UNKNOWN, UNAVAILABLE, ACTIVE }

    @Volatile var state: State = State.UNKNOWN
        private set

    /** Cores the pool is pinned to; 0 until a pin succeeds. */
    @Volatile private var pinnedCores: Int = 0

    /** The cpuset we last pinned under; a platform reassignment invalidates the pin. */
    @Volatile private var pinnedUnder: String? = null

    /** The cpuset the process currently sits in, e.g. `background`. Null if unreadable. */
    fun currentCpuset(): String? = runCatching {
        File("/proc/self/cgroup").readLines()
            .firstOrNull { it.contains(":cpuset:") }
            ?.substringAfterLast(':')
            ?.trim()
            ?.removePrefix("/")
            ?.ifEmpty { "root" }
    }.getOrNull()

    /**
     * Re-pin only when the platform has moved us since last time. Safe to call per work unit: the
     * check is one small file read; the pin is one syscall.
     */
    fun ensureBoosted() {
        val cpuset = currentCpuset()
        if (pinnedCores > 0 && cpuset == pinnedUnder) return
        pinnedCores = runCatching { ai.rightone.finderplus.speech.SpeechBackends.pinFastCores() }
            .getOrDefault(0)
        pinnedUnder = cpuset
        state = if (pinnedCores > 0) State.ACTIVE else State.UNAVAILABLE
        android.util.Log.i(TAG, "cpuset=$cpuset pinned=$pinnedCores fast cores (state=$state)")
    }

    /** Kept for callers that logged the old escalation result; now identical to [ensureBoosted]. */
    fun boost(): Boolean {
        ensureBoosted()
        return state == State.ACTIVE
    }

    private companion object {
        const val TAG = "finderCpuBoost"
    }
}

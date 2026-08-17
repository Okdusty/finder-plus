package ai.dusty.finderplus.index

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-facing performance switches, surfaced on the widget.
 *
 * Deliberately a tiny, synchronously-readable store rather than a flow: it is consulted on the hot path
 * (per work unit, and per model load), and the widget's whole value is that a tap takes effect on the
 * next unit without a restart.
 */
@Singleton
class PerfPrefs @Inject constructor(@ApplicationContext context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("finder-perf", Context.MODE_PRIVATE)

    /**
     * Run heavy models on the GPU (Vulkan) rather than the CPU.
     *
     * Worth exposing because which one wins is genuinely workload-dependent and was not predictable from
     * theory here: the CLIP image encoder is CPU-only and gains ~18x from big cores, while speech is
     * GPU-bound and gained nothing from them. Measured on this device, Vulkan beat CPU-only for speech
     * (47 s versus 71 s per 30 s window) and stayed steady where CPU degraded to 148 s under thermal
     * pressure — but that is one device and one model pairing, so it is a switch, not a hard-coded choice.
     */
    var useGpu: Boolean
        get() = sp.getBoolean(KEY_GPU, true)
        set(v) { sp.edit().putBoolean(KEY_GPU, v).apply() }

    /**
     * Run flat out: no per-unit pause, no cool-down between slices, and never yield for heat or battery
     * short of the platform reporting CRITICAL.
     *
     * CRITICAL is still honoured, and that is not timidity — it is the state immediately before a thermal
     * shutdown, which would kill the process mid-unit and cost more work than the pause saves. Everything
     * below it is now the user's call.
     */
    var unrestricted: Boolean
        get() = sp.getBoolean(KEY_UNRESTRICTED, false)
        set(v) { sp.edit().putBoolean(KEY_UNRESTRICTED, v).apply() }

    fun toggleGpu(): Boolean { useGpu = !useGpu; return useGpu }

    fun toggleUnrestricted(): Boolean { unrestricted = !unrestricted; return unrestricted }

    private companion object {
        const val KEY_GPU = "use_gpu"
        const val KEY_UNRESTRICTED = "unrestricted"
    }
}

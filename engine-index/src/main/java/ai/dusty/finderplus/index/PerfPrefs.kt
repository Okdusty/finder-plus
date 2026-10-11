package ai.dusty.finderplus.index

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ai.dusty.finderplus.settings.AccelerationSettings
import ai.dusty.finderplus.settings.ProcessingSettings
import ai.dusty.finderplus.settings.Settings
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

    init {
        // These switches now live in the settings; carry over what was chosen before they did.
        val old = context.applicationContext.getSharedPreferences("finder-perf", Context.MODE_PRIVATE)
        if (old.contains(KEY_GPU) || old.contains(KEY_UNRESTRICTED)) {
            if (old.contains(KEY_GPU)) Settings[AccelerationSettings.GPU_NATIVE] = old.getBoolean(KEY_GPU, true)
            if (old.contains(KEY_UNRESTRICTED)) Settings[ProcessingSettings.FULL_SPEED] = old.getBoolean(KEY_UNRESTRICTED, false)
            old.edit().clear().apply()
        }
    }

    /**
     * Run heavy models on the GPU (Vulkan) rather than the CPU.
     *
     * Worth exposing because which one wins is genuinely workload-dependent and not predictable from
     * theory: the CLIP image encoder is CPU-only and gains a lot from big cores, while speech is
     * GPU-bound and does not. Vulkan can beat CPU-only for speech and hold steadier under thermal
     * pressure, but that varies by device and model pairing, so it is a switch, not a hard-coded choice.
     */
    var useGpu: Boolean
        get() = AccelerationSettings.nativeGpu()
        set(v) { Settings[AccelerationSettings.GPU_NATIVE] = v }

    /**
     * Run flat out: no per-unit pause, no cool-down between slices, and no stop for battery level. The
     * heat limits ([PowerDecision.tooHot]) still apply: they protect the hardware, not the charge.
     */
    var unrestricted: Boolean
        get() = Settings[ProcessingSettings.FULL_SPEED]
        set(v) { Settings[ProcessingSettings.FULL_SPEED] = v }

    fun toggleGpu(): Boolean { useGpu = !useGpu; return useGpu }

    fun toggleUnrestricted(): Boolean { unrestricted = !unrestricted; return unrestricted }

    private companion object {
        const val KEY_GPU = "use_gpu"
        const val KEY_UNRESTRICTED = "unrestricted"
    }
}

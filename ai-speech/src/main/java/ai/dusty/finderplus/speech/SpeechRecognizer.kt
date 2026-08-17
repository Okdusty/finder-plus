package ai.dusty.finderplus.speech

import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.Segment

/** A resume cursor for a partially-transcribed file. Persisted as the work unit's checkpoint. */
data class TranscribeCursor(val nextChunkStartMs: Long, val lang: String?)

/**
 * Multilingual ASR. Emits one committed batch of [Segment]s per audio chunk together with the
 * advanced cursor, so a 1-hour file resumes mid-file and a kill loses at most one chunk.
 * See docs/design/03-AI-PIPELINE.md and docs/design/08-SPEECH-QWEN3.md.
 */
interface SpeechRecognizer {
    suspend fun transcribe(
        item: MediaItem,
        from: TranscribeCursor,
        emit: suspend (segments: List<Segment>, next: TranscribeCursor) -> Unit,
        isStopRequested: suspend () -> Boolean,
    )

    /** False when the model files are missing, so the engine can skip the pass instead of failing it. */
    fun isReady(): Boolean
}

/** Where the model files live and how it should be executed. */
data class AsrConfig(
    val modelPath: String,
    val projectorPath: String,
    val accelerator: Accelerator = Accelerator.GPU_VULKAN,
    /**
     * Inference threads.
     *
     * Deliberately 4, not the core count. Raising it to 8 was tried and measured *worse*: Android
     * confines this process to the four little cores within seconds of starting, so eight threads
     * oversubscribe four cores and spend their time contending rather than working. Sizing to the
     * cores we are actually granted beats sizing to the cores the chip has.
     */
    val threads: Int = 4,
    /**
     * Audio window handed to the model per inference.
     *
     * 30 s, and measurement says longer windows gain nothing. Doubling to 60 s was tried on the belief
     * that prefill carried a large fixed per-call cost: 497 tokens took 47.4 s while 518 took 40.0 s,
     * which looked like overhead rather than per-token work. It was not — 60 s produced 988 tokens in
     * 96.7 s, i.e. tokens x1.99 and time x2.04. Prefill is **exactly linear** in audio length, and the
     * earlier anomaly was just first-window warm-up.
     *
     * So 30 s is kept for what it does buy: finer resume granularity after a kill, and per-window
     * timestamps precise enough for "match @ 0:02" to mean something.
     */
    val chunkMs: Long = 30_000L,
    /** Language hint; null asks the model to detect it. */
    val languageHint: String? = null,
)

/**
 * What ggml compute devices this build actually registered.
 *
 * Worth asking directly: whether the Vulkan backend is live depends on the APK carrying
 * `libggml-vulkan.so`, the driver exposing a usable compute queue, *and* the loader finding it — and
 * when any of those fails, ggml's documented behaviour is to fall back to CPU silently. Without this
 * probe, "GPU enabled" is an assumption rather than a fact.
 */
object SpeechBackends {

    data class Device(val name: String, val description: String, val type: String, val totalMiB: Long) {
        /** Integrated GPUs count: on a phone that is the only kind there is. */
        val isGpu: Boolean get() = type == "GPU" || type == "IGPU"
    }

    /** Empty when the native library is unavailable. */
    fun devices(): List<Device> {
        if (!AsrNative.available()) return emptyList()
        return runCatching { AsrNative.backends() }.getOrDefault("")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('|')
                if (p.size < 4) null
                else Device(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L)
            }
            .toList()
    }

    fun hasGpu(): Boolean = devices().any { it.isGpu }

    /**
     * Pin the calling thread to the SoC's fast cores so ggml's worker pool does not straddle tiers.
     * No-op-safe: returns 0 when the kernel refuses, which happens whenever the process cpuset has
     * nothing fast in it.
     */
    fun pinFastCores(): Int =
        if (!AsrNative.available()) 0 else runCatching { AsrNative.pinFastCores() }.getOrDefault(0)

    /** What the Vulkan driver itself reports, independent of ggml's own capability detection. */
    fun vulkanCapabilities(): String =
        if (!AsrNative.available()) "" else runCatching { AsrNative.vulkanCaps() }.getOrDefault("")

    fun setVerboseLogging(on: Boolean) {
        if (AsrNative.available()) runCatching { AsrNative.setVerboseLogging(on) }
    }
}

/**
 * JNI bridge to llama.cpp's multimodal (`mtmd`) audio path, which officially supports Qwen3-ASR
 * 0.6B/1.7B. Built into `libfinder_asr.so` for arm64-v8a with the Vulkan backend enabled; the native
 * side falls back to CPU when no usable Vulkan device is present.
 */
internal object AsrNative {

    /** @return opaque context handle, or 0 on failure. */
    external fun init(modelPath: String, projectorPath: String, useGpu: Boolean, threads: Int): Long

    /** Transcribe one PCM window. @return recognized text ("" when nothing was heard). */
    external fun transcribe(ctx: Long, pcm16k: FloatArray, langHint: String?): String

    /**
     * Caption one RGB888 image with a vision-language model loaded via [init] (which never required
     * audio — a vision projector loads through the same path). @return "" on failure.
     */
    external fun caption(ctx: Long, rgb888: ByteArray, width: Int, height: Int, maxTokens: Int, promptTemplate: String): String

    /** Language the model reported for the last window, or "" when unknown. */
    external fun detectedLanguage(ctx: Long): String

    /** True when the context is actually running on the GPU backend. */
    external fun usingGpu(ctx: Long): Boolean

    external fun free(ctx: Long)

    /** `name|description|TYPE|totalMiB` per registered ggml device, newline-separated. */
    external fun backends(): String

    /** Lets llama.cpp's INFO logs through to logcat (backend selection, tensor placement). */
    external fun setVerboseLogging(on: Boolean)

    /** `device|driver|api|extTotal|ext,ext,...` straight from the Vulkan driver. "" if unavailable. */
    external fun vulkanCaps(): String

    /** Pin this thread (and its ggml children) to the fastest cores. @return cores pinned, 0 on refusal. */
    external fun pinFastCores(): Int

    @Volatile private var loadState = 0 // 0 = untried, 1 = loaded, 2 = unavailable

    /** Never throws: a missing native library must degrade to "speech unavailable", not a crash. */
    fun available(): Boolean {
        if (loadState == 0) {
            loadState = try {
                System.loadLibrary("finder_asr"); 1
            } catch (_: Throwable) {
                2
            }
        }
        return loadState == 1
    }
}

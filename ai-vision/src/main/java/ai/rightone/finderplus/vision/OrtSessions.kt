package ai.rightone.finderplus.vision

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Builds ONNX Runtime sessions tuned for this phone, and caches the optimized graph.
 *
 * Two problems this exists to solve, both measured on a Samsung SM-S926B (Exynos 2400, 10 cores)
 * while re-embedding a 3,500-image gallery with CLIP ViT-B/16:
 *
 *  1. **Session creation took minutes.** ORT runs graph optimization on every load, and on a 345 MB
 *     fp32 transformer that is expensive enough to look like a hang — the pass sat in RUNNING, its
 *     lease expired, and it was retried from scratch three times, so it never finished at all.
 *     [setOptimizedModelFilePath] makes ORT write the optimized graph once; later loads read that
 *     directly with optimization disabled, turning minutes into seconds.
 *
 *  2. **Inference used 2 of 10 cores.** ~6.6 s/image. Setting the thread count explicitly on the CPU
 *     provider brought that to ~3.5 s. The remaining gap is not ours to close: Android confines a
 *     background process to the little cores (see [Provider]).
 *
 * Every optimization degrades rather than fails: an unavailable provider or an unwritable cache falls
 * back to a plain CPU session, because a slow index is recoverable and a crashed one is not.
 */
object OrtSessions {

    /**
     * Which compute provider to run on.
     *
     * [NNAPI] matters for a reason unrelated to raw speed: the CPU path is confined by Android to the
     * little cores for a background process (measured 5.3x slower there), and no API lets an app leave
     * that cpuset. Work handed to the NPU/GPU is not subject to it at all, so NNAPI is the only
     * *non-root* way around the platform's scheduling decision.
     *
     * It is deprecated as of API 35 and falls back to CPU per-node for unsupported operators, which can
     * make it slower than plain CPU — so it stays opt-in and measured, never assumed.
     */
    enum class Provider { CPU, NNAPI }

    /**
     * Threads for inference.
     *
     * Two below the core count: the indexer is a background task competing with the UI, decoding, and
     * whatever else the phone is doing, and oversubscribing makes throughput worse rather than better.
     */
    val THREADS: Int = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)

    /**
     * Open [modelPath], preferring a previously optimized copy beside it.
     *
     * @return null when the file is missing or the session cannot be created at all.
     */
    fun create(modelPath: String, provider: Provider = Provider.CPU): OrtSession? {
        if (modelPath.isEmpty()) return null
        val model = File(modelPath)
        if (!model.isFile) return null

        val optimized = File(model.parentFile, "${model.name}.opt")
        // A previously optimized graph is already in ORT's internal form, so re-running the optimizer
        // over it is pure cost. DISABLE_ALL is not "no optimization" here — the optimizations are
        // baked into the file.
        // The optimized-graph cache is only reused for the CPU path. NNAPI partitions the graph
        // itself, and feeding it a graph already rewritten for CPU changes which nodes it can accept.
        if (provider == Provider.CPU && optimized.isFile && optimized.length() > 0) {
            build(optimized.absolutePath, OrtSession.SessionOptions.OptLevel.NO_OPT, null, provider)?.let { return it }
            // A corrupt or version-mismatched cache must not be fatal; drop it and rebuild.
            optimized.delete()
        }

        val writeTo = if (provider == Provider.CPU && model.parentFile?.canWrite() == true) {
            optimized.absolutePath
        } else {
            null
        }
        return build(modelPath, OrtSession.SessionOptions.OptLevel.ALL_OPT, writeTo, provider)
            ?: build(modelPath, OrtSession.SessionOptions.OptLevel.BASIC_OPT, null, provider)
            // An unavailable or unusable provider must degrade to CPU, not to "no search".
            ?: if (provider != Provider.CPU) create(modelPath, Provider.CPU) else null
    }

    private fun build(
        path: String,
        level: OrtSession.SessionOptions.OptLevel,
        optimizedPath: String?,
        provider: Provider,
    ): OrtSession? =
        try {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(level)
                setMemoryPatternOptimization(true)
                optimizedPath?.let { runCatching { setOptimizedModelFilePath(it) } }

                // Plain CPU EP with an explicit thread count.
                //
                // XNNPACK was tried here and measured *worse*: a ViT-B/16 forward took 6.6 s at ~56%
                // total CPU, i.e. barely half a core, because its own thread pool did not pick up the
                // requested width and the session's intra-op threads had been dropped to 1 to avoid
                // contending with it. Setting the threads directly on the CPU provider is both simpler
                // and measurably faster, so the extra provider earns nothing.
                setIntraOpNumThreads(THREADS)
                setInterOpNumThreads(1)

                if (provider == Provider.NNAPI) {
                    // FP16 is what makes the NPU worth using; CPU_DISABLED forces a hard failure
                    // instead of a silent per-node fallback that would look like a working NNAPI run
                    // while actually measuring the CPU.
                    addNnapi(
                        java.util.EnumSet.of(
                            ai.onnxruntime.providers.NNAPIFlags.USE_FP16,
                            ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED,
                        )
                    )
                }
            }
            OrtEnvironment.getEnvironment().createSession(path, opts).also {
                android.util.Log.i(TAG, "session ready: provider=$provider threads=$THREADS opt=$level ${File(path).name}")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "session failed (${File(path).name}, $provider, $level): ${t.message}")
            null
        }

    private const val TAG = "finderOrt"
}

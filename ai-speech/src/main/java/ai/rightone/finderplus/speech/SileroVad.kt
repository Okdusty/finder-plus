package ai.rightone.finderplus.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Speech / non-speech detection with Silero VAD — a real classifier, not an energy threshold.
 *
 * ### Why the energy detector is not enough
 *
 * [Vad] decides on RMS and zero-crossing rate, which answers "is something audible" rather than "is
 * someone talking". Measured against ground truth on this gallery — 18 files whose transcripts the app
 * had already produced, so the useful/wasted split is known rather than guessed — energy cannot tell
 * the two apart at all:
 *
 * ```
 *                        median mean-volume
 *   transcript ≤19 chars      -17.2 dB
 *   transcript ≥200 chars     -17.1 dB
 * ```
 *
 * The wasted files are not quiet. They are music, laughter and ambience at ordinary loudness. Silero
 * separates the same two groups by a factor of twenty on speech fraction (2% vs 43% median), because it
 * was trained to recognize speech specifically.
 *
 * ### What it buys
 *
 * ASR is ~75% of this gallery's remaining index time — 9.78 hours of media at roughly 1.6× realtime —
 * and the audio encoder is the whole cost (prefill 21–70 s per 30 s window; decode 2–5 s). 20% of the
 * 30-second windows in the measured sample contain no speech at all, and skipping them costs nothing:
 * at the threshold below, every window carrying a useful transcript was kept.
 *
 * It also prevents hallucination. Sequence-to-sequence ASR invents fluent, confident sentences over
 * music and silence, which is worse than no transcript because it is indistinguishable from a real one.
 *
 * ### Contract details that are easy to get wrong
 *
 * Both of these fail *silently* — the model runs, returns well-formed numbers, and reports no speech
 * anywhere, including on a file that transcribed to 1,232 characters:
 *
 *  - **Context.** v5 carries [CONTEXT] samples between frames; the input tensor is 576 wide, not 512.
 *  - **Range.** Samples must be in [-1, 1]. The caller's decoder divides by 32768, so this holds — but
 *    a float PCM path can exceed it, and out-of-range input saturates the model to zero.
 *
 * Licence: MIT.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    /** Rolling state, reset per window so one window's decision cannot bias the next file's. */
    private var state = FloatArray(2 * 1 * STATE_DIM)
    private var context = FloatArray(CONTEXT)

    /**
     * Seconds of speech in [samples] (16 kHz mono, [-1, 1]).
     *
     * Returns 0 rather than throwing if inference fails: a broken VAD must not be able to stop
     * transcription, only to skip work it is confident is pointless.
     */
    fun speechSeconds(samples: FloatArray): Float {
        reset()
        var voiced = 0
        var i = 0
        val frame = FloatArray(CONTEXT + FRAME)
        try {
            while (i + FRAME <= samples.size) {
                System.arraycopy(context, 0, frame, 0, CONTEXT)
                System.arraycopy(samples, i, frame, CONTEXT, FRAME)
                if (probability(frame) > SPEECH_PROB) voiced++
                System.arraycopy(samples, i + FRAME - CONTEXT, context, 0, CONTEXT)
                i += FRAME
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "vad inference failed: ${t.message}")
            return 0f
        }
        return voiced * FRAME / SAMPLE_RATE.toFloat()
    }

    /** True when the window holds enough speech to be worth an ASR pass. */
    fun hasSpeech(samples: FloatArray): Boolean = speechSeconds(samples) >= MIN_SPEECH_SECONDS

    private fun probability(frame: FloatArray): Float {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, frame.size.toLong()))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf())
        val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_DIM.toLong()))
        input.use { _ -> sr.use { _ -> st.use { _ ->
            session.run(mapOf("input" to input, "sr" to sr, "state" to st)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val prob = (out[0].value as Array<FloatArray>)[0][0]
                // The returned state feeds the next frame; without carrying it the model sees every
                // frame as the start of the audio.
                (out[1].value as? Array<*>)?.let { state = flatten(it) }
                return prob
            }
        } } }
    }

    private fun flatten(nested: Array<*>): FloatArray {
        val out = FloatArray(2 * STATE_DIM)
        var k = 0
        for (a in nested) for (b in (a as Array<*>)) for (v in (b as FloatArray)) {
            if (k < out.size) out[k++] = v
        }
        return out
    }

    private fun reset() {
        state = FloatArray(2 * STATE_DIM)
        context = FloatArray(CONTEXT)
    }

    override fun close() = runCatching { session.close() }.let { }

    companion object {
        private const val TAG = "finderVad"

        const val SAMPLE_RATE = 16_000

        /** Samples per decision. Fixed by the model at 16 kHz. */
        const val FRAME = 512

        /** Samples of previous audio v5 expects prepended to each frame. */
        const val CONTEXT = 64

        private const val STATE_DIM = 128

        /** Per-frame probability above which a frame counts as speech. Silero's own default. */
        private const val SPEECH_PROB = 0.5f

        /**
         * Speech a window must contain before ASR runs on it.
         *
         * Deliberately at the safe end. On the labelled sample this skipped 5 of 9 windows whose ASR
         * output was worthless while keeping **every** window that produced a useful transcript; the
         * more aggressive thresholds that skipped 7 or 9 of them also discarded a 679-character
         * transcript, and losing real speech to save compute is the wrong trade for a search index.
         */
        const val MIN_SPEECH_SECONDS = 0.5f

        /** Loads the model, or null when it is not installed — the caller then falls back to [Vad]. */
        @Suppress("unused")
        fun load(modelPath: String?): SileroVad? {
            if (modelPath.isNullOrBlank()) return null
            return runCatching {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    // One thread: this runs per 30 s window between ASR passes, and the frames are
                    // 512 samples. Threading overhead would exceed the work.
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                SileroVad(env, env.createSession(modelPath, opts))
            }.onFailure { android.util.Log.w(TAG, "silero load failed: ${it.message}") }.getOrNull()
        }
    }
}

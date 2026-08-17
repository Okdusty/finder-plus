package ai.dusty.finderplus.speech

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cheap energy + zero-crossing voice activity detector over 16 kHz mono PCM.
 *
 * Two reasons this matters a lot here:
 *  1. **Battery** — most gallery videos are mostly silence, music or ambience. Skipping those windows
 *     avoids running a multi-hundred-million-parameter decoder over nothing.
 *  2. **Quality** — sequence-to-sequence ASR models (Whisper and Qwen3-ASR alike) *hallucinate*
 *     confident text on silence. Dropping non-speech windows removes that failure mode outright.
 */
object Vad {

    /** True when [samples] plausibly contains speech. [samples] are floats in roughly [-1, 1]. */
    fun hasSpeech(
        samples: FloatArray,
        rmsThreshold: Float = 0.012f,
        minVoicedFraction: Float = 0.06f,
        frameSize: Int = 400, // 25 ms at 16 kHz
    ): Boolean {
        if (samples.isEmpty()) return false
        var voiced = 0
        var frames = 0
        var i = 0
        while (i + frameSize <= samples.size) {
            frames++
            if (isVoicedFrame(samples, i, frameSize, rmsThreshold)) voiced++
            i += frameSize
        }
        if (frames == 0) return rms(samples, 0, samples.size) >= rmsThreshold
        return voiced.toFloat() / frames >= minVoicedFraction
    }

    private fun isVoicedFrame(s: FloatArray, from: Int, len: Int, rmsThreshold: Float): Boolean {
        val energy = rms(s, from, len)
        if (energy < rmsThreshold) return false
        // Very high zero-crossing rate with modest energy is usually noise/hiss, not speech.
        var crossings = 0
        for (i in from + 1 until from + len) {
            if ((s[i] >= 0f) != (s[i - 1] >= 0f)) crossings++
        }
        val zcr = crossings.toFloat() / len
        return zcr < 0.35f
    }

    private fun rms(s: FloatArray, from: Int, len: Int): Float {
        var sum = 0.0
        for (i in from until from + len) sum += (s[i] * s[i]).toDouble()
        return sqrt(sum / len).toFloat()
    }

    /** Rough peak level, used to skip windows that are digital silence outright. */
    fun peak(samples: FloatArray): Float {
        var p = 0f
        for (s in samples) { val a = abs(s); if (a > p) p = a }
        return p
    }
}

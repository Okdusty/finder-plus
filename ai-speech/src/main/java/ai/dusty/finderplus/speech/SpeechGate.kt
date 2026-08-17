package ai.dusty.finderplus.speech

/**
 * Decides whether an audio window is worth transcribing, using the best detector installed.
 *
 * One seam for both engines so the two cannot drift apart — a window Whisper skips and Qwen3-ASR
 * transcribes would make the two engines' output incomparable, which is the whole point of keeping both.
 *
 * Prefers [SileroVad] and falls back to the energy detector in [Vad]. The fallback is not equivalent and
 * is not pretended to be: energy answers "is something audible", which on this gallery is true of the
 * music and laughter that ASR turns into nothing. It is retained only so a missing model degrades the
 * gate rather than disabling transcription.
 */
class SpeechGate(private val vadPath: () -> String?) {

    private var silero: SileroVad? = null
    private var resolved = false

    /** True when [samples] (16 kHz mono, [-1, 1]) should go to the recognizer. */
    fun shouldTranscribe(samples: FloatArray): Boolean {
        // Digital silence: cheaper to reject here than to run any model, and unambiguous.
        if (Vad.peak(samples) < SILENCE_PEAK) {
            count(skipped = true, speechSeconds = 0f, silent = true)
            return false
        }
        val vad = detector()
        if (vad == null) {
            val ok = Vad.hasSpeech(samples)
            count(skipped = !ok, speechSeconds = -1f, silent = false)
            return ok
        }
        val speech = vad.speechSeconds(samples)
        val ok = speech >= SileroVad.MIN_SPEECH_SECONDS
        count(skipped = !ok, speechSeconds = speech, silent = false)
        return ok
    }

    /**
     * Running skip rate, logged periodically.
     *
     * This exists because the way a misconfigured VAD fails is not an exception — it returns a valid
     * probability of ~0 for every frame and reports no speech anywhere. Two separate wiring mistakes
     * produced exactly that during development (a missing context window, and out-of-range samples), and
     * either would have silently emptied the entire transcript index. A skip rate near 100% is the
     * symptom, so it is made visible rather than left to be discovered hours later.
     */
    @Synchronized
    private fun count(skipped: Boolean, speechSeconds: Float, silent: Boolean) {
        windows++
        if (skipped) skips++
        if (!silent && speechSeconds >= 0f) {
            android.util.Log.d("finderVad", "window ${if (skipped) "SKIP" else "keep"} speech=%.2fs".format(speechSeconds))
        }
        if (windows % REPORT_EVERY == 0) {
            val rate = 100 * skips / windows
            android.util.Log.i("finderVad", "speech gate: $skips/$windows windows skipped ($rate%) via ${describe()}")
            if (rate >= SUSPICIOUS_SKIP_RATE) {
                android.util.Log.w("finderVad", "skip rate $rate% is implausibly high — check the VAD, not the audio")
            }
        }
    }

    private var windows = 0
    private var skips = 0

    /** Which detector is actually in use — logged once, so the choice is never a guess. */
    fun describe(): String = if (detector() != null) "silero" else "energy+zcr (fallback)"

    @Synchronized
    private fun detector(): SileroVad? {
        if (!resolved) {
            resolved = true
            silero = SileroVad.load(vadPath())
            android.util.Log.i(
                "finderVad",
                if (silero != null) "speech gate: silero" else "speech gate: energy+zcr (silero not installed)",
            )
        }
        return silero
    }

    private companion object {
        /** Below this peak the window carries no signal at all. */
        const val SILENCE_PEAK = 0.005f

        const val REPORT_EVERY = 25

        /** Measured on this gallery: ~20% of windows are speechless. Anywhere near 90% means a bug. */
        const val SUSPICIOUS_SKIP_RATE = 90
    }
}

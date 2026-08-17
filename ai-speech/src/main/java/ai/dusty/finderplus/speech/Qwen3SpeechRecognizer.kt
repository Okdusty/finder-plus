package ai.dusty.finderplus.speech

import ai.dusty.finderplus.media.PcmDecoder
import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.Segment

/**
 * Qwen3-ASR transcription via llama.cpp `mtmd`, driven over ~30 s PCM windows.
 *
 * Per window it: runs VAD (skipping silence/music entirely), invokes the model, then hands the text
 * plus the advanced cursor to [emit] — the engine commits both in one transaction, preserving
 * "checkpoint never ahead of committed results", so a kill loses at most one window.
 *
 * Chosen over Whisper because Qwen3-ASR's 30 languages include Turkish, where Whisper's small tiers
 * are weakest. See docs/design/08-SPEECH-QWEN3.md.
 */
class Qwen3SpeechRecognizer(
    private val pcmDecoder: PcmDecoder,
    private val configProvider: () -> AsrConfig?,
    private val speechGate: SpeechGate = SpeechGate { null },
) : SpeechRecognizer, AutoCloseable {

    // Loading the model costs seconds and hundreds of MB of I/O, so the native context is kept open
    // and reused across files. Creating it per file would reload ~1 GB for every one of ~1,300 A/V
    // items, which alone would make a full transcription pass infeasible.
    private var ctx: Long = 0L
    private var ctxKey: String? = null

    override fun isReady(): Boolean {
        val cfg = configProvider() ?: return false
        return AsrNative.available() &&
            cfg.modelPath.isNotEmpty() && cfg.projectorPath.isNotEmpty()
    }

    /** Reuse the loaded context when the model/accelerator has not changed. */
    private fun contextFor(cfg: AsrConfig): Long {
        val key = "${cfg.modelPath}|${cfg.projectorPath}|${cfg.accelerator}|${cfg.threads}"
        if (ctx != 0L && key == ctxKey) return ctx
        close()
        val handle = AsrNative.init(
            cfg.modelPath, cfg.projectorPath,
            useGpu = cfg.accelerator == Accelerator.GPU_VULKAN,
            threads = cfg.threads,
        )
        if (handle != 0L) { ctx = handle; ctxKey = key }
        return handle
    }

    /** Release native memory — called when the model changes or the engine unloads heavy models. */
    override fun close() {
        if (ctx != 0L) {
            AsrNative.free(ctx)
            ctx = 0L
            ctxKey = null
        }
    }

    override suspend fun transcribe(
        item: MediaItem,
        from: TranscribeCursor,
        emit: suspend (segments: List<Segment>, next: TranscribeCursor) -> Unit,
        isStopRequested: suspend () -> Boolean,
    ) {
        val cfg = configProvider()
            ?: throw AsrUnavailableException("no speech model selected")
        if (!AsrNative.available()) {
            throw AsrUnavailableException("speech engine native library not present")
        }

        val ctx = contextFor(cfg)
        if (ctx == 0L) throw AsrUnavailableException("could not load ${cfg.modelPath}")

        var lang = from.lang ?: cfg.languageHint
        run {
            pcmDecoder.decodeChunks(item, fromMs = from.nextChunkStartMs, chunkMs = cfg.chunkMs) { startMs, endMs, samples ->
                if (isStopRequested()) return@decodeChunks false // cooperative stop at a window boundary

                // Skip non-speech windows: saves the decoder entirely and prevents the
                // hallucinated-text-on-silence failure mode these models are prone to.
                if (!speechGate.shouldTranscribe(samples)) {
                    emit(emptyList(), TranscribeCursor(endMs, lang))
                    return@decodeChunks true
                }

                // The model returns "language <X><asr_text>…"; strip that scaffolding, keep the
                // language it reported, and cut degenerate repetition loops.
                val parsed = AsrOutputParser.parse(AsrNative.transcribe(ctx, samples, lang))
                if (lang == null) lang = parsed.language
                val text = if (AsrOutputParser.isJunk(parsed.text)) "" else parsed.text

                val segments = if (text.isEmpty()) {
                    emptyList()
                } else {
                    // The window is the unit of timing: one segment spanning it. Finer word-level
                    // timings would need token timestamps, which this path does not expose.
                    listOf(Segment(item.id, startMs, endMs, text))
                }
                emit(segments, TranscribeCursor(nextChunkStartMs = endMs, lang = lang))
                true
            }
        }
    }
}

/**
 * Thrown when speech models/native code are absent. The engine treats this as "skip", not "fail", so a
 * user without the (large) speech model still gets a fully indexed gallery for images and metadata.
 */
class AsrUnavailableException(message: String) : Exception(message)

package ai.rightone.finderplus.speech

import ai.rightone.finderplus.media.PcmDecoder
import ai.rightone.finderplus.model.Accelerator
import ai.rightone.finderplus.model.MediaItem
import ai.rightone.finderplus.model.Segment

/**
 * Speech recognition via whisper.cpp — the fast engine.
 *
 * Exists because Qwen3-ASR measured **~1.6 s of compute per 1 s of audio** on this device
 * (prefill-dominated and exactly linear in length), which put the remaining 6.4-hour backlog at roughly
 * ten hours. Whisper's encoder is far more heavily optimized, and it is the only alternative that keeps
 * Turkish: NVIDIA Parakeet is faster still but its 25 supported languages exclude it, and Nemotron-ASR
 * ships only NeMo checkpoints with no on-device inference path.
 *
 * Shares the same [SpeechRecognizer] contract, so it is a drop-in swap — the chunked, checkpointed
 * resume behaviour the indexer depends on is unchanged.
 *
 * Two differences from the Qwen3 path worth knowing:
 *  - Whisper emits its **own** segment boundaries, so timings are per utterance rather than one span
 *    covering the whole chunk. That makes "match @ 0:42" land on the actual sentence.
 *  - Whole files go in one call. `whisper_full` does its own 30 s windowing internally and carries
 *    decoder context across those windows, so pre-chunking at 30 s actively threw that away. See
 *    [CHUNK_MS]. One consequence: the silence gate is now per *file* rather than per 30 s, so a long
 *    mostly-silent recording pays encoder cost for its quiet stretches. whisper.cpp's own
 *    `no_speech_thold` still stops it inventing words there, and in this gallery 760 of 964 remaining
 *    files are under 30 s, so the trade is heavily favourable.
 */
class WhisperSpeechRecognizer(
    private val pcmDecoder: PcmDecoder,
    private val configProvider: () -> AsrConfig?,
    private val speechGate: SpeechGate = SpeechGate { null },
) : SpeechRecognizer {

    @Volatile private var ctx: Long = 0
    @Volatile private var loadedPath: String? = null

    override fun isReady(): Boolean =
        WhisperNative.available() && configProvider()?.modelPath?.isNotEmpty() == true

    @Synchronized
    private fun contextFor(cfg: AsrConfig): Long {
        // Cached across files: loading a Whisper model is hundreds of milliseconds to seconds, and the
        // indexer walks thousands of items. Reloading per file was what made the Qwen3 path pay a 1 GB
        // read for every single item.
        if (ctx != 0L && loadedPath == cfg.modelPath) return ctx
        if (ctx != 0L) {
            WhisperNative.free(ctx)
            ctx = 0
        }
        ctx = WhisperNative.init(cfg.modelPath, cfg.accelerator == Accelerator.GPU_VULKAN)
        loadedPath = if (ctx != 0L) cfg.modelPath else null
        return ctx
    }

    override suspend fun transcribe(
        item: MediaItem,
        from: TranscribeCursor,
        emit: suspend (segments: List<Segment>, next: TranscribeCursor) -> Unit,
        isStopRequested: suspend () -> Boolean,
    ) {
        val cfg = configProvider() ?: throw AsrUnavailableException("no speech model selected")
        if (!WhisperNative.available()) {
            throw AsrUnavailableException("whisper native library not present")
        }
        val handle = contextFor(cfg)
        if (handle == 0L) throw AsrUnavailableException("could not load ${cfg.modelPath}")

        var lang = from.lang ?: cfg.languageHint

        pcmDecoder.decodeChunks(item, fromMs = from.nextChunkStartMs, chunkMs = CHUNK_MS) { startMs, endMs, samples ->
            if (isStopRequested()) return@decodeChunks false

            // Same speech gate as the Qwen3 path, and it matters more here: Whisper's best-known
            // failure mode is inventing fluent sentences over music and silence.
            if (!speechGate.shouldTranscribe(samples)) {
                emit(emptyList(), TranscribeCursor(endMs, lang))
                return@decodeChunks true
            }

            val raw = WhisperNative.transcribe(handle, samples, lang, cfg.threads)
            if (lang == null) {
                lang = WhisperNative.detectedLanguage(handle).takeIf { it.isNotBlank() && it != "auto" }
            }

            val segments = parseSegments(item.id, startMs, endMs, raw)
            emit(segments, TranscribeCursor(nextChunkStartMs = endMs, lang = lang))
            true
        }
    }

    /**
     * Parse `t0|t1|text` lines into [Segment]s, rebasing Whisper's window-relative timestamps onto the
     * file's timeline and clamping them into the window.
     *
     * Clamping is not paranoia: Whisper can emit a `t1` past the end of the audio it was given, and an
     * end-before-start segment would make the "seek to match" affordance jump to the wrong place.
     */
    private fun parseSegments(itemId: Long, windowStartMs: Long, windowEndMs: Long, raw: String): List<Segment> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Segment>()
        for (line in raw.lineSequence()) {
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) continue
            val text = parts[2].trim()
            if (text.isEmpty() || AsrOutputParser.isJunk(text)) continue
            val relStart = parts[0].toLongOrNull() ?: 0L
            val relEnd = parts[1].toLongOrNull() ?: relStart
            val start = (windowStartMs + relStart).coerceIn(windowStartMs, windowEndMs)
            val end = (windowStartMs + relEnd).coerceIn(start, windowEndMs)
            out += Segment(itemId, start, end, text)
        }
        return out
    }

    private companion object {
        /**
         * How much audio is handed to `whisper_full` at once — effectively the whole file.
         *
         * whisper.cpp does its **own** 30 s windowing internally, and carries decoder context across
         * those windows (`prompt_past`). Feeding it 30 s at a time therefore fought the library: it paid
         * mel/graph setup per call and threw away the cross-window context that improves both wording and
         * timestamps at window boundaries.
         *
         * 10 minutes rather than literally unbounded, purely to keep the ledger's checkpointing useful:
         * every real gallery file is one call, while a pathological hour-long recording still commits
         * progress six times instead of risking all of it to one process kill. At 16 kHz mono float that
         * is ~38 MB per buffer, which this device has in abundance.
         */
        const val CHUNK_MS = 600_000L
    }
}

/** JNI bridge to whisper.cpp, built into the same `libfinder_asr.so` and sharing llama.cpp's ggml. */
internal object WhisperNative {

    /** @return opaque context handle, or 0 on failure. */
    external fun init(modelPath: String, useGpu: Boolean): Long

    /** @return `t0|t1|text` per segment, newline-separated; timestamps are ms within the window. */
    external fun transcribe(ctx: Long, pcm16k: FloatArray, langHint: String?, threads: Int): String

    external fun detectedLanguage(ctx: Long): String

    external fun free(ctx: Long)

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

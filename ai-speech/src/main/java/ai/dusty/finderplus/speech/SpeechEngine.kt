package ai.dusty.finderplus.speech

import ai.dusty.finderplus.media.PcmDecoder
import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.Segment

/** Which native speech implementation to run. Selected by the installed model's role. */
enum class SpeechEngine { QWEN3, WHISPER }

/**
 * Routes transcription to whichever engine the selected model needs, resolved per call.
 *
 * Per call rather than once at construction, so changing the model in Settings takes effect immediately
 * — the same reason the ASR config itself is resolved lazily. Both engines are constructed, but neither
 * loads a model until it actually runs, so the unused one costs nothing but a few object headers.
 *
 * This is what makes the two engines A/B-able on the user's own files instead of a one-way migration:
 * Whisper is roughly an order of magnitude faster here, while Qwen3-ASR was chosen originally for its
 * Turkish quality, and only listening to both on real audio settles that trade.
 */
class DelegatingSpeechRecognizer(
    pcm: PcmDecoder,
    private val configProvider: (Accelerator) -> AsrConfig?,
    private val engineProvider: () -> SpeechEngine,
    vadPath: () -> String? = { null },
) : SpeechRecognizer {

    // Shared deliberately: both engines must skip exactly the same windows, or their transcripts stop
    // being comparable on the same file.
    private val gate = SpeechGate(vadPath)

    private val qwen3 = Qwen3SpeechRecognizer(
        pcm, configProvider = { configProvider(Accelerator.GPU_VULKAN) }, speechGate = gate,
    )
    private val whisper = WhisperSpeechRecognizer(
        pcm, configProvider = { configProvider(Accelerator.GPU_VULKAN) }, speechGate = gate,
    )

    private fun active(): SpeechRecognizer =
        if (engineProvider() == SpeechEngine.WHISPER) whisper else qwen3

    override fun isReady(): Boolean = active().isReady()

    override suspend fun transcribe(
        item: MediaItem,
        from: TranscribeCursor,
        emit: suspend (segments: List<Segment>, next: TranscribeCursor) -> Unit,
        isStopRequested: suspend () -> Boolean,
    ) = active().transcribe(item, from, emit, isStopRequested)
}

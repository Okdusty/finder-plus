package ai.dusty.finderplus.speech

import android.graphics.Bitmap
import java.io.File

/**
 * One-sentence image captions from a small vision-language model (SmolVLM-256M), through the SAME
 * native loader as ASR — llama.cpp's `mtmd` serves audio and vision projectors identically, which is
 * why this class lives in ai-speech next to its JNI rather than in ai-vision.
 *
 * The context is loaded lazily and kept resident across items (loading costs seconds; the indexer
 * visits thousands of items), same policy as the ASR context and for the same measured reason.
 */
class VlmCaptioner(
    private val modelPath: () -> String?,
    private val projectorPath: () -> String?,
    private val useGpu: () -> Boolean = { true },
    /**
     * Which chat template the loaded weights expect. A wrong template does not error — the model
     * simply produces worse output, which is the most dangerous kind of wrong.
     */
    private val family: Family = Family.SMOL,
) {

    enum class Family { SMOL, QWEN }

    private var ctx: Long = 0L
    private var loadedKey: String? = null

    fun isReady(): Boolean {
        val m = modelPath() ?: return false
        val p = projectorPath() ?: return false
        return File(m).exists() && File(p).exists()
    }

    @Synchronized
    private fun contextFor(): Long {
        val m = modelPath() ?: return 0
        val p = projectorPath() ?: return 0
        val key = "$m|$p|${useGpu()}"
        if (ctx != 0L && loadedKey == key) return ctx
        if (ctx != 0L) { AsrNative.free(ctx); ctx = 0 }
        if (!AsrNative.available()) return 0
        ctx = AsrNative.init(m, p, useGpu(), THREADS)
        loadedKey = if (ctx != 0L) key else null
        return ctx
    }

    /**
     * Caption [bitmap], or "" when the model is missing or inference fails.
     *
     * The bitmap is downscaled before crossing JNI: SmolVLM's vision tower works at 512 px, so
     * shipping a 1024 px decode would quadruple the pixel copy for zero quality.
     */
    fun caption(bitmap: Bitmap, maxTokens: Int = MAX_TOKENS): String {
        val handle = contextFor()
        if (handle == 0L) return ""
        val scaled = downscale(bitmap, VISION_EDGE)
        val rgb = ByteArray(scaled.width * scaled.height * 3)
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        var o = 0
        for (p in px) {
            rgb[o++] = ((p shr 16) and 0xFF).toByte()
            rgb[o++] = ((p shr 8) and 0xFF).toByte()
            rgb[o++] = (p and 0xFF).toByte()
        }
        val out = AsrNative.caption(handle, rgb, scaled.width, scaled.height, maxTokens, promptTemplate(DESCRIBE))
        if (scaled != bitmap) scaled.recycle()
        return out.trim()
    }

    /**
     * Ask an arbitrary question about the image — the judge's entry point. Same context, same
     * residency; only the instruction differs.
     */
    fun ask(bitmap: Bitmap, question: String, maxTokens: Int): String {
        val handle = contextFor()
        if (handle == 0L) return ""
        val scaled = downscale(bitmap, VISION_EDGE)
        val rgb = ByteArray(scaled.width * scaled.height * 3)
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        var o = 0
        for (p in px) {
            rgb[o++] = ((p shr 16) and 0xFF).toByte()
            rgb[o++] = ((p shr 8) and 0xFF).toByte()
            rgb[o++] = (p and 0xFF).toByte()
        }
        val out = AsrNative.caption(handle, rgb, scaled.width, scaled.height, maxTokens, promptTemplate(question))
        if (scaled != bitmap) scaled.recycle()
        return out.trim()
    }

    /** The model-specific chat template, with {MEDIA} standing for the image tokens. */
    private fun promptTemplate(instruction: String): String = when (family) {
        Family.QWEN ->
            "<|im_start|>user\n{MEDIA}$instruction<|im_end|>\n<|im_start|>assistant\n"
        Family.SMOL ->
            "<|im_start|>User: {MEDIA}$instruction<end_of_utterance>\nAssistant:"
    }

    @Synchronized
    fun release() {
        if (ctx != 0L) { AsrNative.free(ctx); ctx = 0; loadedKey = null }
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true,
        )
    }

    companion object {
        /** SmolVLM's native resolution; larger inputs are resized by the tower anyway. */
        private const val VISION_EDGE = 512

        /**
         * Hard decode cap. A caption is search text, not prose — "a man riding a scooter down a
         * street" is 9 tokens — and on a 4,800-item gallery every extra token is decoder time
         * multiplied by the corpus.
         */
        const val MAX_TOKENS = 24

        // "What is happening" pulls a 256M model toward content; heavier anti-medium
        // instructions confuse it more than they help (too small to negate reliably).
        private const val DESCRIBE = "Describe what is happening in this image in one short sentence."

        private const val THREADS = 4
    }
}

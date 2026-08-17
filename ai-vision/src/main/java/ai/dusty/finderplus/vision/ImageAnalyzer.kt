package ai.dusty.finderplus.vision

import android.graphics.Bitmap
import ai.dusty.finderplus.model.Tag
import ai.dusty.finderplus.model.TagSource

data class OcrResult(val fullText: String, val lang: String?, val keywords: List<String>)

/** Whole-image labeler (ImageNet). [isReady] is false until the model is installed. */
interface Labeler {
    fun isReady(): Boolean = true
    suspend fun label(bitmap: Bitmap): List<Tag>
}

/** Text recognizer (Tesseract). [isReady] is false until its trained data is installed. */
interface OcrReader {
    fun isReady(): Boolean = true
    suspend fun read(bitmap: Bitmap): OcrResult
}

/**
 * The image intelligence surface used for both photos and video keyframes. Each method is one
 * micro-batch the engine commits before advancing a checkpoint. See docs/design/03-AI-PIPELINE.md §1.
 */
interface ImageAnalyzer {
    /** False while the labeling model is not installed; the label pass parks rather than record empty. */
    fun labelsReady(): Boolean = true

    /** False while the OCR model is not installed; the OCR pass parks rather than record empty. */
    fun ocrReady(): Boolean = true

    suspend fun labels(bitmap: Bitmap): List<Tag>
    suspend fun ocr(bitmap: Bitmap): OcrResult
    suspend fun embed(bitmap: Bitmap): FloatArray
    fun category(labels: List<Tag>, path: String?): String
}

/** CLIP image tower (index time). */
interface ClipImageEncoder { suspend fun encode(bitmap: Bitmap): FloatArray; val modelId: String; val dim: Int }

/** CLIP text tower (query time) — encodes the search text into the SAME space as image vectors. */
interface ClipTextEncoder {
    suspend fun encode(text: String): FloatArray

    /**
     * Encode many strings in one inference call.
     *
     * This is not a convenience wrapper — it is what makes a large concept vocabulary affordable.
     * Per-call overhead dominates a single 77-token sequence, so encoding N strings one at a time
     * costs roughly N times a batch of N. Building a vocabulary of thousands of concepts is hours of
     * work sequentially and under a minute batched.
     *
     * The default implementation falls back to one-at-a-time so an encoder without batch support is
     * still correct, just slow.
     */
    suspend fun encodeBatch(texts: List<String>): List<FloatArray> = texts.map { encode(it) }

    val modelId: String
    val dim: Int
}

/**
 * ML Kit (labeling + OCR) + ONNX CLIP implementation. ML Kit is lightweight; the CLIP encoder is a
 * heavy model held under the ModelCoordinator's residency lock.
 */
class DefaultImageAnalyzer(
    private val labeler: Labeler,
    private val ocrReader: OcrReader,
    private val clip: ClipImageEncoder,
) : ImageAnalyzer {

    override fun labelsReady(): Boolean = labeler.isReady()

    override fun ocrReady(): Boolean = ocrReader.isReady()

    override suspend fun labels(bitmap: Bitmap): List<Tag> = labeler.label(bitmap)

    override suspend fun ocr(bitmap: Bitmap): OcrResult = ocrReader.read(bitmap)

    override suspend fun embed(bitmap: Bitmap): FloatArray = clip.encode(bitmap)

    override fun category(labels: List<Tag>, path: String?): String {
        val labelSet = labels.map { it.label.lowercase() }.toSet()
        return when {
            path?.contains("screenshot", ignoreCase = true) == true -> "screenshot"
            labelSet.any { it in RECEIPT_HINTS } -> "document"
            labelSet.any { it in FOOD_HINTS } -> "food"
            labelSet.any { it in PEOPLE_HINTS } -> "people"
            labelSet.any { it in NATURE_HINTS } -> "nature"
            else -> "other"
        }
    }

    private companion object {
        val RECEIPT_HINTS = setOf("text", "document", "paper", "receipt", "invoice", "menu")
        val FOOD_HINTS = setOf("food", "dish", "meal", "dessert", "drink")
        val PEOPLE_HINTS = setOf("person", "people", "face", "selfie", "crowd")
        val NATURE_HINTS = setOf("tree", "sky", "mountain", "beach", "flower", "landscape")
    }
}

fun ocrKeywordsAsTags(itemId: Long, keywords: List<String>): List<Tag> =
    keywords.map { Tag(itemId, TagSource.OCR_KEYWORD, it, 1.0f) }

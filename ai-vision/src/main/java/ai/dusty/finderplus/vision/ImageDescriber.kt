package ai.dusty.finderplus.vision

import android.graphics.Bitmap

/** What a vision-language model read in one image. */
data class ImageDescription(
    /** One sentence, or empty when the model produced nothing usable. */
    val caption: String,
    /** Located objects, in the coordinates of the bitmap that was described. */
    val objects: List<DetectedRegion>,
    /** The caption's phrases, located: what the caption says the image is about. */
    val grounded: List<DetectedRegion> = emptyList(),
) {
    companion object {
        val EMPTY = ImageDescription("", emptyList())
    }
}

/** Describes an image in words and located objects, in one read of its pixels. */
interface ImageDescriber {
    /** False while the model is not installed; passes park rather than record empty results. */
    fun isReady(): Boolean

    /** Identifies the installed model, so stored results can be traced to what produced them. */
    val modelId: String?

    suspend fun describe(bitmap: Bitmap): ImageDescription

    /** Frees the loaded model; the next [describe] loads it again. */
    fun unload() {}
}

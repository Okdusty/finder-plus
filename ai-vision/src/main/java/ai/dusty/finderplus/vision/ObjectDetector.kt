package ai.dusty.finderplus.vision

import android.graphics.Bitmap
import ai.dusty.finderplus.model.Tag

/**
 * Multi-object detection. Complements whole-image labeling: a photo of a table gets one dominant
 * label from the labeler, but detection surfaces each distinct object in the frame, plus how many
 * there are — so "two dogs" style content becomes findable.
 *
 * Implemented by [OnnxYoloDetector] (YOLOX-tiny, Apache-2.0), which names the thing — `person`,
 * `cup`, `bicycle` — where the old bundled detector's most frequent output was `multiple objects`.
 */
/**
 * A detected thing and where it is.
 *
 * The box is the point: a whole-frame embedding averages a small object into one of 196 patches, so the
 * only way a general encoder can describe "that specific mug" is to be handed the mug.
 */
data class DetectedRegion(
    val label: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

interface ObjectDetector {
    /** False while the backing model is not installed; passes park rather than record empty results. */
    fun isReady(): Boolean = true

    suspend fun detect(bitmap: Bitmap): List<Tag>

    /** Same detections, boxes retained so a general encoder can be pointed at each region. */
    suspend fun detectRegions(bitmap: Bitmap): List<DetectedRegion> = emptyList()
}

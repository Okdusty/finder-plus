package ai.dusty.finderplus.vision

import android.graphics.Bitmap

/** A detected face: where it is, and how prominent. */
data class DetectedFace(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** Face area as a fraction of the image — prominent faces make better cluster anchors. */
    val areaRatio: Float,
    /** Null when the detector does not classify expression (YuNet) — unknown, not negative. */
    val smiling: Float?,
    val eyesOpen: Float?,
)

/**
 * Face **detection** (not recognition). This stage is deliberately cheap and model-light; it yields
 * search signal on its own (how many people, is it a selfie). Implemented by [OnnxFaceDetector]
 * (YuNet, MIT). See docs/design/09-PEOPLE-AND-VLM.md.
 */
interface FaceAnalyzer {
    /** False while the backing detector model is not installed; the pass parks rather than record empty. */
    fun isReady(): Boolean = true

    suspend fun detect(bitmap: Bitmap): List<DetectedFace>
}

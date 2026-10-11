package ai.dusty.finderplus.vision

/**
 * A named thing and where it is, in pixel coordinates of the image it was found in.
 *
 * The box matters: a whole-image embedding averages a small object away, so checking a label means
 * looking at its region.
 */
data class DetectedRegion(
    val label: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

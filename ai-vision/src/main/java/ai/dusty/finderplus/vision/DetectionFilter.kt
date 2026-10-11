package ai.dusty.finderplus.vision

/**
 * Geometry rules applied to the detector's raw boxes. Kept free of Android so they are unit-tested.
 *
 *  - **One name per region.** Suppression runs across classes, as in the YOLOX reference demo, so a
 *    region the detector scores under several classes keeps only its best one.
 *  - **Only what the caption is about.** Objects are kept where they match a phrase the caption
 *    grounds, so background and parts it does not mention are dropped.
 *  - **A box covering the whole image is not asserted.** The detector has no "none of these" class:
 *    an object outside its vocabulary that fills the frame is forced onto the nearest class, often with
 *    high confidence, so no score threshold separates it from a real close-up. Callers hold such
 *    detections for the user to confirm instead.
 */
object DetectionFilter {

    const val NMS_IOU = 0.45f
    val WHOLE_FRAME: Float
        get() = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.LearningSettings.WHOLE_FRAME].toFloat()

    /** Overlap at which two boxes are the same object: the PASCAL VOC matching criterion. */
    const val SAME_OBJECT_IOU = 0.5f

    /**
     * Objects that are what the image is about: those whose box matches one of [grounded], the caption's
     * located phrases. Background and parts the caption does not mention have no matching box. With no
     * grounded phrase at all there is nothing to judge by, so every object is kept.
     */
    fun groundedOnly(
        objects: List<DetectedRegion>,
        grounded: List<DetectedRegion>,
        sameObjectIou: Float = SAME_OBJECT_IOU,
    ): List<DetectedRegion> {
        if (grounded.isEmpty()) return objects
        return objects.filter { o -> grounded.any { iou(o, it) >= sameObjectIou } }
    }

    fun classAgnosticNms(cands: List<DetectedRegion>, iouThreshold: Float = NMS_IOU): List<DetectedRegion> {
        val sorted = cands.sortedByDescending { it.confidence }.toMutableList()
        val out = ArrayList<DetectedRegion>()
        while (sorted.isNotEmpty()) {
            val keep = sorted.removeAt(0)
            out += keep
            sorted.removeAll { iou(keep, it) > iouThreshold }
        }
        return out
    }

    fun areaFraction(r: DetectedRegion, imageWidth: Int, imageHeight: Int): Float {
        val total = imageWidth.toFloat() * imageHeight
        if (total <= 0f) return 0f
        return ((r.right - r.left).coerceAtLeast(0).toFloat() * (r.bottom - r.top).coerceAtLeast(0)) / total
    }

    fun isWholeFrame(r: DetectedRegion, imageWidth: Int, imageHeight: Int): Boolean =
        areaFraction(r, imageWidth, imageHeight) >= WHOLE_FRAME

    fun iou(a: DetectedRegion, b: DetectedRegion): Float {
        val ix = maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toFloat()
        val iy = maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).toFloat()
        val inter = ix * iy
        val union = (a.right - a.left).toFloat() * (a.bottom - a.top) +
            (b.right - b.left).toFloat() * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }
}

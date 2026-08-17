package ai.dusty.finderplus.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import ai.dusty.finderplus.model.Tag
import ai.dusty.finderplus.model.TagSource
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Object detection with **YOLOX-tiny** (Apache-2.0) via ONNX Runtime — 80 concrete COCO classes.
 *
 * Replaces ML Kit's coarse detector, whose most frequent output on this gallery was literally
 * `multiple objects` (723 items) followed by `fashion good` — categories nobody searches for. YOLOX
 * names the thing: measured on gallery photos it returns `person 0.93`, `cup 0.85`, `bicycle 0.59`.
 *
 * ### Preprocessing contract (validated against the reference implementation on real photos)
 *
 *  - Letterbox to 416×416: scale to fit, pad bottom/right with 114 grey, **no normalization** —
 *    the exported graph takes raw 0-255 floats.
 *  - **BGR channel order.** YOLOX trains on `cv2.imread` output. Measured both ways on 8 gallery
 *    photos: BGR mean top-score 0.770 vs RGB 0.682. Feeding RGB costs accuracy silently.
 *  - Decode: per stride s ∈ {8,16,32}, `xy = (pred + grid) · s`, `wh = exp(pred) · s`; the
 *    objectness/class scores come out of the graph already sigmoided.
 *
 * Threshold 0.40 was picked from the same measurement: person/cup sit at 0.8-0.9, while the noise
 * band (`scissors 0.35`, `remote 0.37` on photos containing neither) sits just under 0.40.
 */
class OnnxYoloDetector(
    private val modelPath: String,
    private val minConfidence: Float = MIN_CONF,
) : ObjectDetector {

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null

    override fun isReady(): Boolean = modelPath.isNotEmpty() && File(modelPath).exists()

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (!isReady()) return null
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // Detection shares the cheap tier with OCR and faces; two threads is the measured
                // sweet spot before it starts stealing from ML Kit's own pool.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            e.createSession(modelPath, opts).also { session = it }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "yolo session failed: ${t.message}")
            null
        }
    }

    override suspend fun detect(bitmap: Bitmap): List<Tag> =
        detectRegions(bitmap).map { r -> Tag(0L, TagSource.OBJECT, r.label, r.confidence) }
            // One tag per distinct class, keeping the strongest instance — the tag table is
            // per-label-unique anyway, and "3 cups" belongs to counting, not labelling.
            .groupBy { it.label }
            .map { (_, tags) -> tags.maxBy { it.confidence } }

    override suspend fun detectRegions(bitmap: Bitmap): List<DetectedRegion> {
        val s = ensureSession() ?: return emptyList()
        val (input, scale) = preprocess(bitmap)
        return try {
            val tensor = OnnxTensor.createTensor(
                env ?: OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()),
            )
            tensor.use {
                s.run(mapOf(s.inputNames.first() to tensor)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = (out[0].value as Array<Array<FloatArray>>)[0]
                    postprocess(raw, scale, bitmap.width, bitmap.height)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "yolo inference failed: ${t.message}")
            emptyList()
        }
    }

    /** Letterbox into a 416×416 grey canvas, BGR, raw 0-255. Returns the tensor and the scale used. */
    private fun preprocess(src: Bitmap): Pair<FloatArray, Float> {
        val scale = min(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(src, nw, nh, true)

        val out = FloatArray(3 * SIZE * SIZE) { PAD_GREY }
        val px = IntArray(nw * nh)
        resized.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (resized != src) resized.recycle()
        val plane = SIZE * SIZE
        for (y in 0 until nh) {
            val rowOff = y * SIZE
            val srcOff = y * nw
            for (x in 0 until nw) {
                val p = px[srcOff + x]
                // BGR: blue plane first. See the class doc — this order was measured, not assumed.
                out[rowOff + x] = (p and 0xFF).toFloat()
                out[plane + rowOff + x] = ((p shr 8) and 0xFF).toFloat()
                out[2 * plane + rowOff + x] = ((p shr 16) and 0xFF).toFloat()
            }
        }
        return out to scale
    }

    private fun postprocess(raw: Array<FloatArray>, scale: Float, imgW: Int, imgH: Int): List<DetectedRegion> {
        val cands = ArrayList<DetectedRegion>()
        var row = 0
        for (stride in STRIDES) {
            val n = SIZE / stride
            for (gy in 0 until n) for (gx in 0 until n) {
                val p = raw[row++]
                var best = 0
                var bestScore = 0f
                for (c in 0 until NUM_CLASSES) {
                    val sc = p[5 + c]
                    if (sc > bestScore) { bestScore = sc; best = c }
                }
                val conf = p[4] * bestScore
                if (conf < minConfidence) continue
                val cx = (p[0] + gx) * stride
                val cy = (p[1] + gy) * stride
                val w = exp(p[2]) * stride
                val h = exp(p[3]) * stride
                cands += DetectedRegion(
                    label = COCO[best],
                    confidence = conf,
                    left = ((cx - w / 2) / scale).toInt().coerceIn(0, imgW),
                    top = ((cy - h / 2) / scale).toInt().coerceIn(0, imgH),
                    right = ((cx + w / 2) / scale).toInt().coerceIn(0, imgW),
                    bottom = ((cy + h / 2) / scale).toInt().coerceIn(0, imgH),
                )
            }
        }
        return nmsPerClass(cands)
    }

    private fun nmsPerClass(cands: List<DetectedRegion>): List<DetectedRegion> {
        val out = ArrayList<DetectedRegion>()
        for ((_, group) in cands.groupBy { it.label }) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val keep = sorted.removeAt(0)
                out += keep
                sorted.removeAll { iou(keep, it) > NMS_IOU }
            }
        }
        return out.sortedByDescending { it.confidence }
    }

    private fun iou(a: DetectedRegion, b: DetectedRegion): Float {
        val ix = max(0, min(a.right, b.right) - max(a.left, b.left)).toFloat()
        val iy = max(0, min(a.bottom, b.bottom) - max(a.top, b.top)).toFloat()
        val inter = ix * iy
        val union = (a.right - a.left).toFloat() * (a.bottom - a.top) +
            (b.right - b.left).toFloat() * (b.bottom - b.top) - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        private const val TAG = "finderYolo"
        const val SIZE = 416
        private const val PAD_GREY = 114f
        private const val NUM_CLASSES = 80
        private const val MIN_CONF = 0.40f
        private const val NMS_IOU = 0.45f
        private val STRIDES = intArrayOf(8, 16, 32)

        /** COCO-80, index-aligned with the model's class logits. */
        val COCO = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )
    }
}

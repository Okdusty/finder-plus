package ai.rightone.finderplus.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Face **detection** with **YuNet** (`face_detection_yunet_2023mar`, MIT, OpenCV Zoo) via ONNX
 * Runtime — the FOSS replacement for the bundled ML Kit detector.
 *
 * Detection only; there is deliberately no identity stage (grouping the same person across photos
 * needed a margin-trained face model whose only practical weights are non-commercial). The boxes and
 * their prominence already yield useful search signal: how many people, and whether one dominates the
 * frame (a selfie/portrait).
 *
 * ### Contract (validated against OpenCV's own FaceDetectorYN on real gallery photos)
 *
 *  - input  : NCHW float32 [1, 3, 640, 640], **BGR**, raw 0-255 — no normalization. The image is
 *             resized straight to 640x640 (the model's fixed input), and boxes are scaled back by the
 *             same factor.
 *  - outputs: per stride s ∈ {8,16,32} over grids {80,40,20}: `cls_s`/`obj_s` [1, N, 1],
 *             `bbox_s` [1, N, 4], `kps_s` [1, N, 10]. cls and obj come out of the graph already
 *             sigmoided; score = sqrt(cls · obj), matching OpenCV.
 *  - decode : `cx = (bbox₀ + col)·s`, `cy = (bbox₁ + row)·s`, `w = exp(bbox₂)·s`, `h = exp(bbox₃)·s`;
 *             then per-class-free NMS.
 *
 * Measured agreement with OpenCV's reference on gallery faces: box IoU 0.79-0.83 (the gap is the
 * 640 letterbox vs OpenCV's native-size run), score within 0.02.
 *
 * Returns an empty list when the model is not installed; the pass reads [isReady] and parks instead.
 */
class OnnxFaceDetector(
    private val modelPath: String,
    private val minScore: Float = MIN_SCORE,
) : FaceAnalyzer {

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null

    override fun isReady(): Boolean = modelPath.isNotEmpty() && File(modelPath).exists()

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (!isReady()) return null
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // Face crops are cheap; two threads avoids contending with the rest of the cheap tier.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            e.createSession(modelPath, opts).also { session = it }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "yunet session failed: ${t.message}")
            null
        }
    }

    override suspend fun detect(bitmap: Bitmap): List<DetectedFace> {
        val s = ensureSession() ?: return emptyList()
        val input = preprocess(bitmap)
        return try {
            val tensor = OnnxTensor.createTensor(
                env ?: OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()),
            )
            tensor.use {
                // Run with the single input; fetch every output by name (cls/obj/bbox per stride).
                s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                    val byName = s.outputNames.associateWith { name -> result.get(name).get().value }
                    postprocess(byName, bitmap.width, bitmap.height)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "yunet inference failed: ${t.message}")
            emptyList()
        }
    }

    /** Straight resize to 640x640, BGR, raw 0-255, NCHW. */
    private fun preprocess(src: Bitmap): FloatArray {
        val resized = if (src.width == SIZE && src.height == SIZE) src
                      else Bitmap.createScaledBitmap(src, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        resized.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (resized != src) resized.recycle()
        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = (p and 0xFF).toFloat()                      // B
            out[plane + i] = ((p shr 8) and 0xFF).toFloat()      // G
            out[2 * plane + i] = ((p shr 16) and 0xFF).toFloat() // R
        }
        return out
    }

    private fun postprocess(out: Map<String, Any?>, imgW: Int, imgH: Int): List<DetectedFace> {
        val scaleX = imgW.toFloat() / SIZE
        val scaleY = imgH.toFloat() / SIZE
        val cands = ArrayList<FloatArray>() // x,y,w,h,score in 640 space
        for (si in STRIDES.indices) {
            val stride = STRIDES[si]
            val fm = GRIDS[si]
            val cls = flatten(out["cls_$stride"])
            val obj = flatten(out["obj_$stride"])
            val bbox = reshape4(out["bbox_$stride"])
            if (cls == null || obj == null || bbox == null) continue
            for (i in 0 until fm * fm) {
                if (i >= cls.size || i >= obj.size || i >= bbox.size) break
                val score = sqrt((cls[i].coerceIn(0f, 1f)) * (obj[i].coerceIn(0f, 1f)))
                if (score < minScore) continue
                val row = i / fm
                val col = i % fm
                val b = bbox[i]
                val cx = (b[0] + col) * stride
                val cy = (b[1] + row) * stride
                val w = exp(b[2]) * stride
                val h = exp(b[3]) * stride
                cands += floatArrayOf(cx - w / 2, cy - h / 2, w, h, score)
            }
        }
        val kept = nms(cands)
        val imageArea = (imgW.toFloat() * imgH).coerceAtLeast(1f)
        return kept.map { c ->
            val left = (c[0] * scaleX).toInt().coerceIn(0, imgW)
            val top = (c[1] * scaleY).toInt().coerceIn(0, imgH)
            val right = ((c[0] + c[2]) * scaleX).toInt().coerceIn(0, imgW)
            val bottom = ((c[1] + c[3]) * scaleY).toInt().coerceIn(0, imgH)
            DetectedFace(
                left = left, top = top, right = right, bottom = bottom,
                areaRatio = ((right - left).toFloat() * (bottom - top)) / imageArea,
                // YuNet does not classify expression; smiling/eyes are unknown, not negative.
                smiling = null, eyesOpen = null,
            )
        }
    }

    private fun nms(cands: List<FloatArray>): List<FloatArray> {
        val sorted = cands.sortedByDescending { it[4] }.toMutableList()
        val out = ArrayList<FloatArray>()
        while (sorted.isNotEmpty()) {
            val keep = sorted.removeAt(0)
            out += keep
            sorted.removeAll { iou(keep, it) > NMS_IOU }
        }
        return out
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val union = a[2] * a[3] + b[2] * b[3] - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun flatten(v: Any?): FloatArray? = when (v) {
        is Array<*> -> (v.firstOrNull() as? Array<*>)?.let { outer ->
            // [1, N, 1]
            FloatArray(outer.size) { ((outer[it] as? FloatArray)?.firstOrNull()) ?: 0f }
        }
        is FloatArray -> v
        else -> null
    }

    private fun reshape4(v: Any?): Array<FloatArray>? = when (v) {
        is Array<*> -> (v.firstOrNull() as? Array<*>)?.map { it as FloatArray }?.toTypedArray()
        else -> null
    }

    private companion object {
        private const val TAG = "finderYuNet"
        private const val SIZE = 640
        private const val MIN_SCORE = 0.6f
        private const val NMS_IOU = 0.3f
        private val STRIDES = intArrayOf(8, 16, 32)
        private val GRIDS = intArrayOf(80, 40, 20)
    }
}

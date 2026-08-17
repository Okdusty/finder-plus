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

/**
 * Image labeling with **MobileNetV2** (Apache-2.0) over ImageNet-1k via ONNX Runtime — the FOSS
 * replacement for the bundled ML Kit labeler this app used before going fully open.
 *
 * Preprocessing follows the torchvision validation transform, which is what the model was converted
 * from and measured against — mismatching it silently degrades top-1 rather than failing:
 *  - resize shortest side to 256, center-crop 224x224
 *  - RGB, scaled to [0, 1], then normalized by ImageNet mean/std
 *  - NCHW float32 [1, 3, 224, 224]; output is 1000 logits, softmaxed here
 *
 * Validated against the reference preprocessing on a real gallery photo ("menu" top-1 at 0.60).
 *
 * ImageNet has no "person" class — people come from the face detector and YOLOX instead. This model
 * covers objects, animals, food, scenes and documents, which is what the label pass is for.
 *
 * Returns an empty list when the model is not installed; the pass reads [isReady] and parks instead.
 */
class OnnxImageLabeler(
    private val modelPath: String,
    private val minConfidence: Float = 0.30f,
    private val maxLabels: Int = 8,
) : Labeler {

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null

    override fun isReady(): Boolean = modelPath.isNotEmpty() && File(modelPath).exists()

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (!isReady()) return null
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // Labeling shares the cheap tier with OCR, faces and detection.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env = e
            e.createSession(modelPath, opts).also { session = it }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "labeler session failed: ${t.message}")
            null
        }
    }

    override suspend fun label(bitmap: Bitmap): List<Tag> {
        val s = ensureSession() ?: return emptyList()
        val input = preprocess(bitmap)
        return try {
            val tensor = OnnxTensor.createTensor(
                env ?: OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong()),
            )
            tensor.use {
                s.run(mapOf(s.inputNames.first() to tensor)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (out[0].value as Array<FloatArray>)[0]
                    topK(logits)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "labeler inference failed: ${t.message}")
            emptyList()
        }
    }

    /** Resize shortest side to 256, center-crop 224, RGB [0,1] normalized by ImageNet mean/std, NCHW. */
    private fun preprocess(src: Bitmap): FloatArray {
        val scale = RESIZE.toFloat() / minOf(src.width, src.height)
        val rw = Math.round(src.width * scale).coerceAtLeast(SIDE)
        val rh = Math.round(src.height * scale).coerceAtLeast(SIDE)
        val resized = Bitmap.createScaledBitmap(src, rw, rh, true)
        val x = (rw - SIDE) / 2
        val y = (rh - SIDE) / 2
        val cropped = Bitmap.createBitmap(resized, x, y, SIDE, SIDE)
        if (resized != src && resized != cropped) resized.recycle()

        val pixels = IntArray(SIDE * SIDE)
        cropped.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        if (cropped != src) cropped.recycle()

        val out = FloatArray(3 * SIDE * SIDE)
        val plane = SIDE * SIDE
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = ((((p shr 16) and 0xFF) / 255f) - MEAN[0]) / STD[0]
            out[plane + i] = ((((p shr 8) and 0xFF) / 255f) - MEAN[1]) / STD[1]
            out[2 * plane + i] = (((p and 0xFF) / 255f) - MEAN[2]) / STD[2]
        }
        return out
    }

    private fun topK(logits: FloatArray): List<Tag> {
        // softmax in a numerically stable way, then take the strongest few above the floor.
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0f
        val probs = FloatArray(logits.size)
        for (i in logits.indices) {
            val e = exp(logits[i] - max)
            probs[i] = e
            sum += e
        }
        val idx = probs.indices.sortedByDescending { probs[it] }
        val out = ArrayList<Tag>()
        for (i in idx) {
            val p = probs[i] / sum
            if (p < minConfidence) break
            out += Tag(0L, TagSource.LABEL, ImageNetLabels.ALL[i], p)
            if (out.size >= maxLabels) break
        }
        return out
    }

    private companion object {
        private const val TAG = "finderLabeler"
        private const val RESIZE = 256
        private const val SIDE = 224
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

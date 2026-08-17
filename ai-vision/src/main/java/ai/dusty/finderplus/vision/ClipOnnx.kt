package ai.dusty.finderplus.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap

/**
 * CLIP image tower via ONNX Runtime.
 *
 * Preprocessing follows the CLIP reference exactly — mismatching it silently destroys retrieval quality
 * rather than failing loudly:
 *  - resize shortest side to 224 and center-crop to 224x224 (bicubic-ish via Android's filtered scale)
 *  - RGB, scaled to [0, 1], then normalized by CLIP's mean/std
 *  - NCHW float32 [1, 3, 224, 224]; output L2-normalized so cosine == dot product
 *
 * Returns a zero vector when no model is installed, which the search engine already treats as "this
 * leg is unavailable" and falls back to FTS.
 */
class OnnxClipImageEncoder(
    private val modelPath: String,
    override val modelId: String,
    override val dim: Int,
    /** Compute provider. Overridden only by the benchmark, which A/Bs CPU against the NPU. */
    private val provider: OrtSessions.Provider = OrtSessions.Provider.CPU,
) : ClipImageEncoder {

    private var session: OrtSession? = null

    @Synchronized
    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        return OrtSessions.create(modelPath, provider)?.also { session = it }
    }

    override suspend fun encode(bitmap: Bitmap): FloatArray {
        val s = ensureSession() ?: return FloatArray(dim)
        return try {
            val input = ClipPreprocess.toTensorData(bitmap)
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                java.nio.FloatBuffer.wrap(input),
                longArrayOf(1, 3, ClipPreprocess.SIDE.toLong(), ClipPreprocess.SIDE.toLong()),
            )
            tensor.use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val vec = when (val raw = r[0].value) {
                        is Array<*> -> raw.firstOrNull() as? FloatArray
                        is FloatArray -> raw
                        else -> null
                    } ?: return FloatArray(dim)
                    ClipPreprocess.normalize(vec)
                }
            }
        } catch (_: Throwable) {
            FloatArray(dim)
        }
    }
}

/**
 * CLIP text tower via ONNX Runtime, tokenized by [ClipTokenizer].
 *
 * Text and image towers are trained to land in one shared space, so a text vector is directly
 * comparable to a stored image vector by dot product. That is what makes "find the sunset photos"
 * work with no such tag in the database, and what lets a label the user has never demonstrated still
 * be scored — see [ZeroShotClassifier].
 *
 * Returns zeros when the model or vocabulary is missing; callers read that as "text leg unavailable"
 * and fall back to keyword search. Encoding into a *wrong* space is the failure worth avoiding here:
 * it produces confident nonsense rather than an obvious outage.
 */
class OnnxClipTextEncoder(
    private val modelPath: String,
    private val vocabPath: String,
    private val mergesPath: String,
    override val modelId: String,
    override val dim: Int,
) : ClipTextEncoder {

    private var session: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null
    private var attempted = false

    /** null = untried, true/false = whether this export accepts a batch axis > 1. */
    @Volatile private var batchSupported: Boolean? = null

    @Synchronized
    private fun ensure(): Pair<OrtSession, ClipTokenizer>? {
        val s = session
        val t = tokenizer
        if (s != null && t != null) return s to t
        if (attempted) return null
        attempted = true

        val tok = ClipTokenizer.load(java.io.File(vocabPath), java.io.File(mergesPath)) ?: return null
        val sess = OrtSessions.create(modelPath) ?: return null
        session = sess
        tokenizer = tok
        return sess to tok
    }

    /** True once the graph and vocabulary are both loadable — lets callers skip work that needs text. */
    fun isReady(): Boolean = ensure() != null

    override suspend fun encode(text: String): FloatArray =
        encodeBatch(listOf(text)).firstOrNull() ?: FloatArray(dim)

    override suspend fun encodeBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val (s, tok) = ensure() ?: return texts.map { FloatArray(dim) }

        // Some exports pin the batch axis to 1. Rather than parse the graph's shape metadata (which
        // may be absent), try the batch and fall back to single rows if the runtime rejects it. The
        // outcome is remembered so the failure is paid once, not per call.
        if (texts.size > 1 && batchSupported != false) {
            runBatch(s, tok, texts)?.let { batchSupported = true; return it }
            batchSupported = false
        }
        return texts.map { one -> runBatch(s, tok, listOf(one))?.firstOrNull() ?: FloatArray(dim) }
    }

    private fun runBatch(s: OrtSession, tok: ClipTokenizer, texts: List<String>): List<FloatArray>? {
        val n = texts.size
        val ctx = ClipTokenizer.CONTEXT
        val ids = Array(n) { tok.encode(texts[it]) }
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(n.toLong(), ctx.toLong())
        val tensors = HashMap<String, OnnxTensor>()
        return try {
            for (name in s.inputNames) {
                // Exports disagree on int32 vs int64 ids, and some take an attention mask; build each
                // declared input to its own declared type instead of assuming one convention.
                val info = s.inputInfo[name]?.info as? ai.onnxruntime.TensorInfo
                val isMask = name.contains("mask", ignoreCase = true)
                val flat = IntArray(n * ctx)
                for (r in 0 until n) {
                    for (c in 0 until ctx) {
                        val v = ids[r][c]
                        flat[r * ctx + c] = if (isMask) (if (v != 0) 1 else 0) else v
                    }
                }
                tensors[name] = if (info?.type == ai.onnxruntime.OnnxJavaType.INT32) {
                    OnnxTensor.createTensor(env, java.nio.IntBuffer.wrap(flat), shape)
                } else {
                    OnnxTensor.createTensor(
                        env,
                        java.nio.LongBuffer.wrap(LongArray(flat.size) { flat[it].toLong() }),
                        shape,
                    )
                }
            }
            s.run(tensors).use { r ->
                when (val raw = r[0].value) {
                    is Array<*> -> raw.map {
                        (it as? FloatArray)?.let(ClipPreprocess::normalize) ?: FloatArray(dim)
                    }
                    is FloatArray -> listOf(ClipPreprocess.normalize(raw))
                    else -> null
                }?.takeIf { it.size == n }
            }
        } catch (_: Throwable) {
            null
        } finally {
            tensors.values.forEach { it.close() }
        }
    }
}

/** CLIP's reference image preprocessing, shared so index-time and any future query-time use agree. */
object ClipPreprocess {
    const val SIDE = 224
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    fun toTensorData(bitmap: Bitmap): FloatArray {
        // Resize shortest side to SIDE, then center-crop — CLIP's documented transform.
        val scale = SIDE.toFloat() / minOf(bitmap.width, bitmap.height)
        val rw = Math.round(bitmap.width * scale).coerceAtLeast(SIDE)
        val rh = Math.round(bitmap.height * scale).coerceAtLeast(SIDE)
        val resized = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val x = (rw - SIDE) / 2
        val y = (rh - SIDE) / 2
        val cropped = Bitmap.createBitmap(resized, x, y, SIDE, SIDE)
        if (resized != bitmap && resized != cropped) resized.recycle()

        val pixels = IntArray(SIDE * SIDE)
        cropped.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        if (cropped != bitmap) cropped.recycle()

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

    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (f in v) n += f * f
        n = kotlin.math.sqrt(n)
        return if (n <= 0f) v else FloatArray(v.size) { v[it] / n }
    }
}

package ai.dusty.finderplus.text

import android.graphics.Bitmap
import ai.dusty.finderplus.vision.ImageDescriber
import ai.dusty.finderplus.vision.ImageDescription
import ai.dusty.finderplus.vision.OrtSessions
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Florence-2 (Microsoft, MIT) through ONNX Runtime: a caption, located objects, and the caption's own
 * phrases located, from one pass of the vision encoder followed by one encoder–decoder run per task.
 *
 * Everything that differs between checkpoints is read from the files they ship: image size,
 * normalization and task prompts from `preprocessor_config.json`, decoding rules from `config.json`,
 * and layer and head counts from the decoder graph. So any Florence-2 export with the same four graphs
 * runs unchanged.
 *
 * Decoding is greedy with the model's `no_repeat_ngram_size`; beam search would multiply the
 * per-token cost on a phone.
 */
class FlorenceDescriber(
    private val files: () -> Files?,
    private val id: () -> String?,
) : ImageDescriber {

    /** The seven files of one checkpoint. */
    data class Files(
        val visionEncoder: String,
        val encoder: String,
        val embedTokens: String,
        val decoder: String,
        val tokenizer: String,
        val config: String,
        val preprocessor: String,
    ) {
        val all get() = listOf(visionEncoder, encoder, embedTokens, decoder, tokenizer, config, preprocessor)
    }

    private class Loaded(
        val key: Files,
        val vision: OrtSession,
        val encoder: OrtSession,
        val embed: OrtSession,
        val decoder: OrtSession,
        val tokenizer: HfBpeTokenizer,
        val setup: Setup,
    ) : AutoCloseable {
        override fun close() { vision.close(); encoder.close(); embed.close(); decoder.close() }
    }

    /** What the checkpoint's config files say. */
    private class Setup(
        val width: Int, val height: Int,
        val mean: FloatArray, val std: FloatArray, val rescale: Float,
        val prompts: Map<String, String>,
        /** Prompts for tasks that take text, with `{input}` where it goes. */
        val promptsWithInput: Map<String, String>,
        val decoderStart: Int, val forcedBos: Int, val eos: Int, val noRepeat: Int,
        val layers: Int, val heads: Int, val headDim: Int,
        val locBins: Int,
    )

    private val lock = Any()
    @Volatile private var loaded: Loaded? = null
    private var failedFor: Files? = null

    override val modelId: String? get() = id()

    override fun isReady(): Boolean = files()?.all?.all { File(it).isFile } == true

    override suspend fun describe(bitmap: Bitmap): ImageDescription {
        val m = ensure() ?: return ImageDescription.EMPTY
        val env = OrtEnvironment.getEnvironment()
        val features = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels(bitmap, m.setup)),
            longArrayOf(1, 3, m.setup.height.toLong(), m.setup.width.toLong())).use { px ->
            m.vision.run(mapOf(m.vision.inputNames.first() to px)).use { r ->
                val t = r[0] as OnnxTensor
                Features(t.floatBuffer.let { b -> FloatArray(b.remaining()).also(b::get) }, (t.info as TensorInfo).shape)
            }
        }
        val caption = generate(m, features, CAPTION_TASK, CAPTION_TOKENS)
        val objects = generate(m, features, OBJECTS_TASK, OBJECT_TOKENS)
        val text = m.tokenizer.decode(caption.ids.filterNot { m.tokenizer.isAdded(it) }.toIntArray()).trim()
        fun regions(g: Generated) = FlorenceOutput.regions(
            ids = g.ids, probs = g.probs,
            locOf = { locIndex(m.tokenizer, it) },
            text = { ids -> m.tokenizer.decode(ids.filterNot { m.tokenizer.isAdded(it) }.toIntArray()) },
            width = bitmap.width, height = bitmap.height, bins = m.setup.locBins,
        )
        // Locate what the caption mentions, so objects can be limited to what the image is about.
        val grounded = if (text.isBlank() || GROUNDING_TASK !in m.setup.promptsWithInput) emptyList()
            else regions(generate(m, features, GROUNDING_TASK, OBJECT_TOKENS, input = text))
        return ImageDescription(text, regions(objects), grounded)
    }

    private class Features(val data: FloatArray, val shape: LongArray)
    private class Generated(val ids: IntArray, val probs: FloatArray)

    /** Greedy decoding of one task; [Generated] holds the new tokens (without the start token) and their probabilities. */
    private fun generate(m: Loaded, image: Features, task: String, maxNew: Int, input: String? = null): Generated {
        val env = OrtEnvironment.getEnvironment()
        val s = m.setup
        val prompt = if (input != null) {
            requireNotNull(s.promptsWithInput[task]) { "the model has no prompt for $task" }.replace("{input}", input)
        } else requireNotNull(s.prompts[task]) { "the model has no prompt for $task" }
        val promptIds = m.tokenizer.encode(prompt)
        val hidden = image.shape[2].toInt()
        val promptEmbeds = embed(m, promptIds)

        // Encoder input: the image tokens followed by the prompt's embeddings.
        val imageLen = image.shape[1].toInt()
        val total = imageLen + promptIds.size
        val encIn = FloatArray(total * hidden)
        System.arraycopy(image.data, 0, encIn, 0, imageLen * hidden)
        System.arraycopy(promptEmbeds, 0, encIn, imageLen * hidden, promptIds.size * hidden)
        val maskData = LongArray(total) { 1L }

        // The encoder output and the first decoder step stay open to the end: every step reads the
        // encoder states and the cross-attention cache the first step produced. Each later step's
        // results only feed the step after it.
        val kept = ArrayList<OrtSession.Result>()
        var previous: OrtSession.Result? = null
        val tensors = ArrayList<OnnxTensor>()
        try {
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(maskData), longArrayOf(1, total.toLong())).also(tensors::add)
            val encInT = OnnxTensor.createTensor(env, FloatBuffer.wrap(encIn), longArrayOf(1, total.toLong(), hidden.toLong())).also(tensors::add)
            val encOut = m.encoder.run(mapOf("inputs_embeds" to encInT, "attention_mask" to mask)).also(kept::add)
            val encHidden = encOut[0] as OnnxTensor

            val past = HashMap<String, OnnxTensor>()
            val empty = longArrayOf(1, s.heads.toLong(), 0, s.headDim.toLong())
            for (name in m.decoder.inputNames) if (name.startsWith("past_key_values.")) {
                past[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), empty).also(tensors::add)
            }
            val outNames = m.decoder.outputNames.toList()
            val seq = arrayListOf(s.decoderStart)
            val ids = ArrayList<Int>(); val probs = ArrayList<Float>()

            for (step in 0 until maxNew) {
                val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(embed(m, intArrayOf(seq.last()))), longArrayOf(1, 1, hidden.toLong())).also(tensors::add)
                val feeds = HashMap<String, OnnxTensor>(past)
                feeds["inputs_embeds"] = e
                feeds["encoder_hidden_states"] = encHidden
                feeds["encoder_attention_mask"] = mask
                if ("use_cache_branch" in m.decoder.inputNames) {
                    feeds["use_cache_branch"] = OnnxTensor.createTensor(env, booleanArrayOf(step > 0)).also(tensors::add)
                }
                val r = m.decoder.run(feeds)
                if (step == 0) kept += r
                val logits = (r[0] as OnnxTensor).floatBuffer
                val vocab = logits.remaining()
                val row = FloatArray(vocab).also(logits::get)
                for (i in 1 until outNames.size) {
                    val name = outNames[i].replace("present.", "past_key_values.")
                    // The cross-attention cache depends only on the encoder output; it is fixed after the first step.
                    if (step > 0 && ".encoder." in name) continue
                    past[name] = r[i] as OnnxTensor
                }
                previous?.close()
                previous = if (step == 0) null else r
                val next = if (step == 0 && s.forcedBos >= 0) s.forcedBos else {
                    for (b in FlorenceOutput.bannedNext(seq, s.noRepeat)) if (b in row.indices) row[b] = Float.NEGATIVE_INFINITY
                    argmax(row)
                }
                val p = softmaxAt(row, next)
                seq += next
                if (next == s.eos) break
                if (next != s.forcedBos || step > 0) { ids += next; probs += p }
            }
            return Generated(ids.toIntArray(), probs.toFloatArray())
        } finally {
            runCatching { previous?.close() }
            kept.forEach { runCatching { it.close() } }
            tensors.forEach { runCatching { it.close() } }
        }
    }

    private fun embed(m: Loaded, ids: IntArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        return OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), longArrayOf(1, ids.size.toLong())).use { t ->
            m.embed.run(mapOf("input_ids" to t)).use { r ->
                val b = (r[0] as OnnxTensor).floatBuffer
                FloatArray(b.remaining()).also(b::get)
            }
        }
    }

    private fun argmax(row: FloatArray): Int {
        var best = 0
        for (i in 1 until row.size) if (row[i] > row[best]) best = i
        return best
    }

    private fun softmaxAt(row: FloatArray, index: Int): Float {
        var max = Float.NEGATIVE_INFINITY
        for (v in row) if (v > max) max = v
        var sum = 0.0
        for (v in row) if (v != Float.NEGATIVE_INFINITY) sum += kotlin.math.exp((v - max).toDouble())
        return if (sum <= 0.0 || row[index] == Float.NEGATIVE_INFINITY) 0f else (kotlin.math.exp((row[index] - max).toDouble()) / sum).toFloat()
    }

    private fun pixels(bitmap: Bitmap, s: Setup): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, s.width, s.height, true)
        val px = IntArray(s.width * s.height)
        scaled.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (scaled !== bitmap) scaled.recycle()
        val plane = s.width * s.height
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = px[i]
            out[i] = (((c shr 16) and 0xFF) * s.rescale - s.mean[0]) / s.std[0]
            out[plane + i] = (((c shr 8) and 0xFF) * s.rescale - s.mean[1]) / s.std[1]
            out[2 * plane + i] = ((c and 0xFF) * s.rescale - s.mean[2]) / s.std[2]
        }
        return out
    }

    private val locCache = HashMap<Int, Int>()

    /** The bin a `<loc_N>` token stands for, or -1 for any other token. */
    private fun locIndex(t: HfBpeTokenizer, id: Int): Int = synchronized(locCache) {
        locCache.getOrPut(id) { LOC.matchEntire(t.tokenOf(id).orEmpty())?.groupValues?.get(1)?.toInt() ?: -1 }
    }

    /** Closes the sessions; the next [describe] loads them again. */
    override fun unload() {
        synchronized(lock) {
            loaded?.close()
            loaded = null
            failedFor = null
        }
    }

    private fun ensure(): Loaded? {
        val f = files() ?: return null
        loaded?.takeIf { it.key == f }?.let { return it }
        synchronized(lock) {
            loaded?.let { if (it.key == f) return it else { it.close(); loaded = null } }
            if (failedFor == f || !isReady()) return null
            failedFor = f
            val sessions = ArrayList<OrtSession>()
            try {
                val vision = OrtSessions.create(f.visionEncoder, DESCRIBE_HARDWARE)?.also(sessions::add) ?: return null
                val encoder = OrtSessions.create(f.encoder, DESCRIBE_HARDWARE)?.also(sessions::add) ?: return null
                val embed = OrtSessions.create(f.embedTokens, DESCRIBE_HARDWARE)?.also(sessions::add) ?: return null
                val decoder = OrtSessions.create(f.decoder, DESCRIBE_HARDWARE)?.also(sessions::add) ?: return null
                val tokenizer = HfBpeTokenizer.load(File(f.tokenizer))
                val setup = readSetup(f, decoder, tokenizer)
                failedFor = null
                return Loaded(f, vision, encoder, embed, decoder, tokenizer, setup).also { loaded = it; sessions.clear() }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "florence load failed: ${t.message}")
                return null
            } finally {
                sessions.forEach { runCatching { it.close() } }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readSetup(f: Files, decoder: OrtSession, tokenizer: HfBpeTokenizer): Setup {
        fun json(path: String) = File(path).bufferedReader().use { JsonPull(it).nextValue() } as Map<String, Any?>
        val pre = json(f.preprocessor)
        val cfg = json(f.config)
        val text = (cfg["text_config"] as? Map<String, Any?>).orEmpty()
        fun num(key: String): Int? = ((text[key] ?: cfg[key]) as? Double)?.toInt()
        val size = pre["size"] as Map<String, Any?>
        fun floats(key: String) = (pre[key] as List<Double>).map { it.toFloat() }.toFloatArray()
        val kv = decoder.inputInfo.entries.first { it.key.endsWith(".decoder.key") }.value.info as TensorInfo
        val layers = decoder.inputNames.count { it.endsWith(".decoder.key") }
        // The number of <loc_N> tokens is the number of position bins across the image.
        val bins = (0 until tokenizer.vocabSize).count { LOC.matches(tokenizer.tokenOf(it).orEmpty()) }
        return Setup(
            width = (size["width"] as Double).toInt(), height = (size["height"] as Double).toInt(),
            mean = floats("image_mean"), std = floats("image_std"),
            rescale = (pre["rescale_factor"] as? Double)?.toFloat() ?: (1f / 255f),
            prompts = (pre["task_prompts_without_inputs"] as Map<String, String>),
            promptsWithInput = (pre["task_prompts_with_input"] as? Map<String, String>).orEmpty(),
            decoderStart = requireNotNull(num("decoder_start_token_id")) { "config has no decoder_start_token_id" },
            forcedBos = num("forced_bos_token_id") ?: -1,
            eos = requireNotNull(num("eos_token_id")) { "config has no eos_token_id" },
            noRepeat = num("no_repeat_ngram_size") ?: 0,
            layers = layers, heads = kv.shape[1].toInt(), headDim = kv.shape[3].toInt(),
            locBins = bins,
        )
    }

    companion object {
        private const val TAG = "finderFlorence"
        private val DESCRIBE_HARDWARE = ai.dusty.finderplus.settings.AccelerationSettings.DESCRIBE
        private val LOC = Regex("<loc_(\\d+)>")

        /** Florence's task names, keys into the prompts its preprocessor config ships. */
        const val CAPTION_TASK = "<CAPTION>"
        const val OBJECTS_TASK = "<OD>"
        const val GROUNDING_TASK = "<CAPTION_TO_PHRASE_GROUNDING>"

        /** Upper bounds that stop a degenerate generation; real outputs end well before them. */
        const val CAPTION_TOKENS = 48
        const val OBJECT_TOKENS = 256
    }
}

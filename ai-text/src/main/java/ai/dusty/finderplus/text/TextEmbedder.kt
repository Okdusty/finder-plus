package ai.dusty.finderplus.text

import ai.dusty.finderplus.vision.OrtSessions
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a piece of text is for. Retrieval models embed questions and answers differently. */
enum class TextRole { QUERY, DOCUMENT }

/**
 * Multilingual sentence embedder. The same model embeds item text at index time and the query at
 * search time, so a query in one language can match text in another.
 * See docs/design/03-AI-PIPELINE.md §4.
 */
interface TextEmbedder {
    /**
     * L2-normalized embedding of [text]; all zeros when the model is not installed. Throws when an
     * installed model fails — a failure must be retried, not stored as "this item has no text".
     */
    suspend fun embed(text: String, role: TextRole = TextRole.DOCUMENT): FloatArray
    fun dimension(): Int
    val modelId: String

    /** True when the model files are present, so callers park work instead of writing zeros. */
    fun isReady(): Boolean
}

/**
 * EmbeddingGemma 2 (Google DeepMind, Apache-2.0) through ONNX Runtime: the text tower of the 740M
 * multimodal model, ~174 MB. It retrieves across languages with no translation step. The 4-bit
 * weights stay within 0.989 mean cosine of fp32 at half the size of the 8-bit export.
 *
 * Queries and documents take different instruction prefixes (from the model's
 * `config_sentence_transformers.json`). Using one prefix for both flattens retrieval, which is why
 * [TextRole] is part of the interface.
 *
 * The 768-d output is Matryoshka-trained. [dims] defaults to 256, which keeps most of the full-size
 * retrieval quality (MTEB multilingual 60.41 vs 61.36) at a third of the storage and scan cost, and
 * search scans every stored vector on each keystroke. It is read per call, so a change in the settings
 * applies to the next text embedded.
 */
class EmbeddingGemmaEmbedder(
    private val modelPath: () -> String?,
    private val tokenizerPath: () -> String?,
    override val modelId: String,
    private val dims: () -> Int = { 256 },
    private val maxTokens: Int = 512,
    /** A fixed provider with no fallback to the CPU, for measuring it; null follows the settings. */
    private val provider: ai.dusty.finderplus.vision.OrtSessions.Provider? = null,
) : TextEmbedder {

    private val dim: Int get() = dims()

    private var session: OrtSession? = null
    private var sessionFor: String? = null
    private var tokenizer: HfBpeTokenizer? = null
    private var tokenizerFor: String? = null

    override fun dimension(): Int = dim

    override fun isReady(): Boolean = modelPath() != null && tokenizerPath() != null

    /** Model and tokenizer, loaded once and reloaded only if their files move (a reinstall). */
    @Synchronized
    private fun ensure(): Pair<OrtSession, HfBpeTokenizer>? {
        val mp = modelPath() ?: return null
        val tp = tokenizerPath() ?: return null
        if (tokenizer == null || tokenizerFor != tp) {
            val t0 = System.currentTimeMillis()
            tokenizer = runCatching { HfBpeTokenizer.load(File(tp)) }
                .onFailure { android.util.Log.w(TAG, "tokenizer load failed: $it") }
                .getOrNull() ?: return null
            tokenizerFor = tp
            android.util.Log.i(TAG, "tokenizer ready in ${System.currentTimeMillis() - t0} ms")
        }
        if (session == null || sessionFor != mp) {
            session?.close()
            session = (if (provider != null) OrtSessions.create(mp, provider, fallback = false)
                else OrtSessions.create(mp, ai.dusty.finderplus.settings.AccelerationSettings.MEANING)) ?: return null
            sessionFor = mp
        }
        return session!! to tokenizer!!
    }

    /** Frees ~200 MB. The next [embed] reloads lazily. */
    @Synchronized
    fun close() {
        session?.close(); session = null; sessionFor = null
        tokenizer = null; tokenizerFor = null
    }

    override suspend fun embed(text: String, role: TextRole): FloatArray = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext FloatArray(dim)
        val (s, tok) = ensure() ?: throw IllegalStateException("text model is installed but failed to load")
        val prompt = when (role) {
            TextRole.QUERY -> QUERY_PREFIX
            TextRole.DOCUMENT -> DOCUMENT_PREFIX
        } + LanguageUtils.normalize(text).take(MAX_CHARS)

        try {
            val ids = tok.encode(prompt, maxTokens)
            val env = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, ids.size.toLong())
            val inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape)
            val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            // The multimodal export also takes image/video/audio soft tokens. Text-only use passes
            // them with zero rows (not rows of zeros) so they contribute nothing.
            val empties = s.inputNames.filter { it.endsWith("_features") }.associateWith {
                OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, FEATURE_WIDTH))
            }
            try {
                val feeds = mapOf("input_ids" to inputIds, "attention_mask" to mask) + empties
                s.run(feeds, setOf(OUTPUT)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val full = (r[0].value as Array<FloatArray>)[0]
                    matryoshka(full, dim)
                }
            } finally {
                inputIds.close(); mask.close(); empties.values.forEach { it.close() }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "embed failed: $t")
            throw t
        }
    }

    companion object {
        private const val TAG = "finderEmbed"
        const val QUERY_PREFIX = "task: search result | query: "
        const val DOCUMENT_PREFIX = "title: none | text: "
        private const val OUTPUT = "sentence_embedding"
        private const val FEATURE_WIDTH = 512L

        /** Characters, not tokens: bounds tokenizer work before the token cap trims the rest. */
        private const val MAX_CHARS = 4_000

        /** First [dim] components, re-normalized — how MRL embeddings are meant to be shortened. */
        fun matryoshka(v: FloatArray, dim: Int): FloatArray {
            val out = v.copyOf(minOf(dim, v.size))
            var norm = 0f
            for (x in out) norm += x * x
            norm = kotlin.math.sqrt(norm)
            if (norm > 0f) for (i in out.indices) out[i] /= norm
            return out
        }
    }
}

object LanguageUtils {
    /** Best-effort language tag for a transcript/OCR string (script + heuristics). */
    fun detect(text: String): String? = null // TODO: lightweight n-gram / script detector

    /** Normalize for embedding/FTS: trim, collapse whitespace, lowercase where script-appropriate. */
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}

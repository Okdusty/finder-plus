package ai.dusty.finderplus.text

/**
 * Multilingual sentence embedder. The SAME instance embeds transcript chunks at index time and the
 * search query at query time, so cross-language semantic transcript match works. Backed by an ONNX
 * model (e.g. multilingual-MiniLM / bge-m3-lite) via ONNX Runtime Mobile.
 * See docs/design/03-AI-PIPELINE.md §4.
 */
interface TextEmbedder {
    /** Returns an L2-normalized embedding of [text]. */
    suspend fun embed(text: String): FloatArray
    fun dimension(): Int
    val modelId: String
}

/**
 * ONNX-backed implementation. The session is created lazily and held under the ModelCoordinator's
 * residency lock (this is a "heavy" model). Tokenization + pooling are model-specific; wired once the
 * chosen .onnx + tokenizer assets are bundled/downloaded.
 */
class OnnxTextEmbedder(
    private val modelPath: String,
    override val modelId: String,
    private val dim: Int,
) : TextEmbedder {

    // private var session: ai.onnxruntime.OrtSession? = null   // created on first use, closed on unload

    override fun dimension(): Int = dim

    override suspend fun embed(text: String): FloatArray {
        // TODO(native): tokenize -> OrtSession.run -> mean-pool -> normalize.
        // Placeholder keeps the module compiling and the pipeline wired end-to-end.
        return FloatArray(dim)
    }
}

object LanguageUtils {
    /** Best-effort language tag for a transcript/OCR string (script + heuristics). */
    fun detect(text: String): String? = null // TODO: lightweight n-gram / script detector

    /** Normalize for embedding/FTS: trim, collapse whitespace, lowercase where script-appropriate. */
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}

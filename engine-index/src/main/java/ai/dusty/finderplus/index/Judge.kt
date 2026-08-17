package ai.rightone.finderplus.index

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** A judge's answer to "is this X?". [UNSURE] leaves the question for the human — never guessed away. */
enum class Verdict { YES, NO, UNSURE }

/** One inference's worth of answers: a verdict per asked label, plus a rewritten caption when offered. */
data class ItemJudgement(val verdicts: List<Verdict>, val caption: String?)

/**
 * Something that can answer review questions about an image, and rewrite a weak caption.
 *
 * The entry point is deliberately *batched*: [judgeItem] takes every pending label for one image and
 * expects one inference to answer them all plus produce the caption. On the local judge this is the
 * difference between viable and not — prefill of the image dominates (~190 s measured at 320 px on the
 * Xclipse 940), so each extra question costs a few decode tokens (~1 s each) instead of a second
 * prefill. The old shape burned one full prefill per label *and another* for the caption.
 */
interface Judge {
    fun isReady(): Boolean
    fun judgeItem(image: Bitmap, labels: List<String>): ItemJudgement
    fun name(): String

    /**
     * How slow one [judgeItem] may be before the run declares the judge broken.
     *
     * Judge-specific because "slow" means different things: the cloud answering in 30 s is a network
     * problem; the local 4B taking minutes is just this GPU (no cooperative matrices). Local mode is
     * therefore an overnight trickle by design, not a failure.
     */
    fun perItemCeilingMs(): Long
}

/**
 * Parse a model's free-text answer into a verdict.
 *
 * Deliberately strict: only an answer that *starts* by committing ("yes", "no") counts. A hedged
 * "it could be..." is exactly the case that belongs with the human, and mapping hedges to NO would
 * silently delete labels the model merely couldn't see well.
 */
internal fun parseVerdict(raw: String): Verdict {
    val t = raw.trim().lowercase()
    return when {
        t.startsWith("yes") -> Verdict.YES
        t.startsWith("no") -> Verdict.NO
        else -> Verdict.UNSURE
    }
}

/**
 * One prompt asking every question at once. Numbered yes/no lines keep each answer independently
 * parseable; the trailing "C:" line collects the caption in the same inference.
 */
internal fun batchPrompt(labels: List<String>): String = buildString {
    // Example-based on purpose: told 'answer as "N: yes"', Qwen3.5-4B wrote the letter N literally.
    // A two-line example fixed format adherence completely (measured over ollama runs).
    append("Reply with exactly one line per numbered question: the question number, a colon, ")
    append("then yes, no, or unsure. Example reply:\n1: yes\n2: no\n\n")
    labels.forEachIndexed { i, l -> append("${i + 1}. Does this image show \"$l\"?\n") }
    // Content, not medium: a caption reading "a screenshot of an app" indexes the wrapper, and the
    // wrapper is never what anyone searches for.
    append("On the final line write \"C:\" followed by one short sentence saying what is happening ")
    append("and who or what is shown. Describe the content, not the medium - do not say screenshot, photo, or image.")
}

/**
 * Parse the batched reply. Anything that fails to parse stays [Verdict.UNSURE] — a malformed line is
 * indistinguishable from a hedge, and both belong with the human rather than guessed either way.
 */
internal fun parseJudgement(raw: String, count: Int): ItemJudgement {
    val verdicts = MutableList(count) { Verdict.UNSURE }
    var caption: String? = null
    for (line in raw.lines()) {
        val t = line.trim()
        if (t.isEmpty()) continue
        val m = NUMBERED.find(t)
        if (m != null) {
            val idx = (m.groupValues[1].toIntOrNull() ?: 0) - 1
            if (idx in 0 until count) verdicts[idx] = parseVerdict(m.groupValues[2])
            continue
        }
        if (t.length > 2 && (t[0] == 'C' || t[0] == 'c') && t[1] == ':') {
            caption = t.substring(2).trim().takeIf { it.isNotBlank() }
        }
    }
    return ItemJudgement(verdicts, caption)
}

private val NUMBERED = Regex("""^(\d+)\s*[:.)\-]\s*(.+)$""")

/** Decode budget for a batched ask: a few tokens per answer line plus one short caption sentence. */
internal fun batchMaxTokens(labelCount: Int): Int = labelCount * 8 + 40

/**
 * The local judge — a 4B multimodal Qwen through the same mtmd context machinery as the captioner.
 *
 * **GPU is not optional here.** A 4B prefill on the little cores would take minutes per image; the
 * judge ignores the widget's CPU/GPU switch and requests the GPU unconditionally — ggml itself falls
 * back to CPU only when no Vulkan device exists at all, which is the one case where CPU is acceptable
 * because it is the only option.
 */
class LocalJudge(
    private val captioner: ai.rightone.finderplus.speech.VlmCaptioner,
) : Judge {
    override fun isReady(): Boolean = captioner.isReady()
    override fun name(): String = "local-qwen3.5-4b"

    /** Drop the ~3 GB native context. Reloaded lazily on the next run. */
    fun release() = captioner.release()

    override fun perItemCeilingMs(): Long = 8 * 60_000L

    override fun judgeItem(image: Bitmap, labels: List<String>): ItemJudgement {
        val out = captioner.ask(downTo(image, JUDGE_EDGE), batchPrompt(labels), batchMaxTokens(labels.size))
        return parseJudgement(out, labels.size)
    }

    /**
     * Qwen's dynamic-resolution tower makes prefill roughly linear in pixel count, so shrinking the
     * input is the one real speed lever on this GPU: 500 px measured 318 s; 320 px cuts the token
     * count ~2.5x. A yes/no about the dominant subject survives 320 px; fine OCR would not, but that
     * is not this model's job.
     */
    private fun downTo(src: Bitmap, edge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= edge) return src
        return Bitmap.createScaledBitmap(src, src.width * edge / longest, src.height * edge / longest, true)
    }

    private companion object { const val JUDGE_EDGE = 320 }
}

/**
 * Which API answers when the user chooses remote assist. [OLLAMA] is the odd one out: not a cloud at
 * all but an Ollama server on a machine the user owns — same privacy story as on-device, desktop
 * speed. Over USB debugging `adb reverse tcp:11434 tcp:11434` makes the phone's `127.0.0.1:11434`
 * reach the computer with no network configuration at all.
 */
enum class CloudProvider { ANTHROPIC, OPENAI, GOOGLE, OPENROUTER, OLLAMA }

/** Sensible per-provider default, editable in the assist settings. */
fun defaultModelFor(p: CloudProvider): String = when (p) {
    CloudProvider.ANTHROPIC -> "claude-opus-5"
    CloudProvider.OPENAI -> "gpt-5.1"
    CloudProvider.GOOGLE -> "gemini-3-flash"
    CloudProvider.OPENROUTER -> "anthropic/claude-haiku-4.5"
    CloudProvider.OLLAMA -> "qwen3.5:4b"
}

/**
 * The cloud judge — one image + one batched prompt per item, against whichever provider the user
 * configured. Only ever constructed when the user has typed an API key.
 *
 * Raw HTTP on purpose: four endpoints called from an offline-first Android app, and the image never
 * persists anywhere but the request. OpenAI and OpenRouter share a wire format (OpenRouter is
 * deliberately OpenAI-compatible); Anthropic and Google each have their own.
 */
class CloudJudge(
    private val provider: () -> CloudProvider,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val ollamaUrl: () -> String = { "http://127.0.0.1:11434" },
) : Judge {

    // Ollama is keyless — your own machine doesn't charge you. Every real cloud needs its key.
    override fun isReady(): Boolean =
        provider() == CloudProvider.OLLAMA || !apiKey().isNullOrBlank()
    override fun name(): String = "cloud-${provider().name.lowercase()}-${model()}"
    override fun perItemCeilingMs(): Long =
        if (provider() == CloudProvider.OLLAMA) 120_000L else 30_000L

    override fun judgeItem(image: Bitmap, labels: List<String>): ItemJudgement {
        val text = complete(image, batchPrompt(labels), batchMaxTokens(labels.size))
            ?: return ItemJudgement(List(labels.size) { Verdict.UNSURE }, null)
        return parseJudgement(text, labels.size)
    }

    private fun complete(image: Bitmap, question: String, maxTokens: Int): String? {
        if (provider() == CloudProvider.OLLAMA) return ollamaNative(image, question, maxTokens)
        val key = apiKey() ?: return null
        return when (provider()) {
            CloudProvider.ANTHROPIC -> anthropic(key, image, question, maxTokens)
            CloudProvider.OPENAI -> openAiStyle("https://api.openai.com/v1/chat/completions", key, image, question, maxTokens)
            CloudProvider.OPENROUTER -> openAiStyle(
                "https://openrouter.ai/api/v1/chat/completions", key, image, question, maxTokens,
                tokensField = "max_tokens",
            )
            CloudProvider.GOOGLE -> google(key, image, question, maxTokens)
            CloudProvider.OLLAMA -> null // handled above
        }
    }

    private fun anthropic(key: String, image: Bitmap, question: String, maxTokens: Int): String? = runCatching {
        val body = JSONObject()
            .put("model", model())
            .put("max_tokens", maxTokens)
            // Thinking would spend the token budget before the terse answers arrive.
            .put("thinking", JSONObject().put("type", "disabled"))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(
                                JSONObject().put("type", "image").put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "image/jpeg")
                                        .put("data", jpegBase64(image)),
                                )
                            )
                            .put(JSONObject().put("type", "text").put("text", question)),
                    )
                ),
            )
        val payload = post(
            "https://api.anthropic.com/v1/messages", body,
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
        ) ?: return null
        val json = JSONObject(payload)
        // A refusal is a valid 200 with empty content — treat as UNSURE, not as "no".
        if (json.optString("stop_reason") == "refusal") return null
        val content = json.optJSONArray("content") ?: return null
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") return block.optString("text")
        }
        null
    }.onFailure { android.util.Log.w(TAG, "anthropic judge failed: ${it.message}") }.getOrNull()

    /**
     * Ollama's native chat API. Not the OpenAI-compat endpoint, deliberately: only the native API
     * takes `think: false`, and a hybrid-thinking Qwen3.5 without it spends the whole `num_predict`
     * budget reasoning and returns empty content — measured, every single call. Falls back to a
     * request without the flag for models that reject it.
     */
    private fun ollamaNative(image: Bitmap, question: String, maxTokens: Int): String? = runCatching {
        fun body(withThink: Boolean) = JSONObject()
            .put("model", model())
            .put("stream", false)
            .apply { if (withThink) put("think", false) }
            .put("options", JSONObject().put("num_predict", maxTokens))
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", question)
                        .put("images", JSONArray().put(jpegBase64(image)))
                ),
            )
        val url = "${ollamaUrl().trimEnd('/')}/api/chat"
        val payload = post(url, body(withThink = true), emptyMap())
            ?: post(url, body(withThink = false), emptyMap())
            ?: return null
        JSONObject(payload).optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
    }.onFailure { android.util.Log.w(TAG, "ollama judge failed: ${it.message}") }.getOrNull()

    /** OpenAI chat-completions shape; OpenRouter accepts the same body and auth header. */
    private fun openAiStyle(
        url: String, key: String, image: Bitmap, question: String, maxTokens: Int,
        // OpenAI's newer models reject the legacy name; Ollama and OpenRouter honour only it.
        tokensField: String = "max_completion_tokens",
    ): String? = runCatching {
        val body = JSONObject()
            .put("model", model())
            .put(tokensField, maxTokens)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", question))
                            .put(
                                JSONObject().put("type", "image_url").put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,${jpegBase64(image)}"),
                                )
                            ),
                    )
                ),
            )
        val payload = post(url, body, mapOf("Authorization" to "Bearer $key")) ?: return null
        val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0) ?: return null
        choice.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
    }.onFailure { android.util.Log.w(TAG, "openai-style judge failed: ${it.message}") }.getOrNull()

    private fun google(key: String, image: Bitmap, question: String, maxTokens: Int): String? = runCatching {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject().put("mime_type", "image/jpeg").put("data", jpegBase64(image)),
                                )
                            )
                            .put(JSONObject().put("text", question)),
                    )
                ),
            )
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        // Key goes in a header, not the query string — URLs end up in logs; headers don't.
        val payload = post(
            "https://generativelanguage.googleapis.com/v1beta/models/${model()}:generateContent",
            body, mapOf("x-goog-api-key" to key),
        ) ?: return null
        val parts = JSONObject(payload).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (p.optBoolean("thought")) continue // reasoning parts are not the answer
            val t = p.optString("text")
            if (t.isNotBlank()) return t
        }
        null
    }.onFailure { android.util.Log.w(TAG, "google judge failed: ${it.message}") }.getOrNull()

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val payload = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: return null
        if (code !in 200..299) {
            android.util.Log.w(TAG, "cloud judge HTTP $code: ${payload.take(200)}")
            return null
        }
        return payload
    }

    /** 512 px JPEG, no Base64 line-wrapping — APIs reject newlines inside the data field. */
    private fun jpegBase64(src: Bitmap): String {
        val longest = maxOf(src.width, src.height)
        val scaled = if (longest <= EDGE) src else Bitmap.createScaledBitmap(
            src, src.width * EDGE / longest, src.height * EDGE / longest, true,
        )
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (scaled != src) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val TAG = "finderJudge"
        const val EDGE = 512
    }
}

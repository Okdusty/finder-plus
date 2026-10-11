package ai.dusty.finderplus.speech

/** Cleaned ASR output: the transcript text plus the language the model reported. */
data class AsrOutput(val text: String, val language: String?)

/**
 * Qwen3-ASR emits a small structured preamble before the transcript, e.g.
 *
 *     language Chinese<asr_text>啊啊啊！
 *     language English<asr_text>I need you. I love you.
 *     language None<asr_text>
 *
 * Left raw, that scaffolding poisoned the search index (every transcript contained the literal words
 * "language" and "asr_text", so those became meaningless high-frequency terms) and the detected
 * language — which the model hands us for free — was thrown away instead of stored.
 *
 * This also truncates *degenerate repetition*. Greedy decoding on music or noise reliably loops
 * ("oh, oh, oh, …" ×24), which inflates the index with junk and makes FTS ranking worse.
 */
object AsrOutputParser {

    private val PREAMBLE = Regex("""^\s*language\s+([A-Za-z]+)\s*<asr_text>""", RegexOption.IGNORE_CASE)
    private val TRAILING_TAG = Regex("""</?asr_text>|<\|[^|]*\|>""")

    fun parse(raw: String): AsrOutput {
        var language: String? = null
        var text = raw

        PREAMBLE.find(text)?.let { m ->
            language = m.groupValues[1].takeIf { !it.equals("None", ignoreCase = true) }
            text = text.removeRange(m.range)
        }
        text = TRAILING_TAG.replace(text, "")
        text = collapseRepetition(text.trim())
        return AsrOutput(text.trim(), language)
    }

    /**
     * Collapse runs of the same token/phrase repeated beyond [maxRepeats]. Keeps a couple of copies so
     * genuine emphasis survives ("no, no") while a 24× loop is cut down to something harmless.
     */
    fun collapseRepetition(text: String, maxRepeats: Int = 3): String {
        if (text.isEmpty()) return text

        // 1. Repeated whitespace-delimited tokens (covers "oh, oh, oh, …").
        val tokens = text.split(' ')
        if (tokens.size > maxRepeats) {
            val out = ArrayList<String>(tokens.size)
            var run = 0
            var prev: String? = null
            for (t in tokens) {
                val key = t.trim().trimEnd(',', '.', '!', '?').lowercase()
                if (key.isNotEmpty() && key == prev) {
                    run++
                    if (run >= maxRepeats) continue
                } else {
                    run = 0; prev = key
                }
                out += t
            }
            if (out.size != tokens.size) return out.joinToString(" ")
        }

        // 2. Repeated single characters with no spaces (CJK loops like 哎哎哎…).
        val sb = StringBuilder(text.length)
        var run = 0
        var prevCh: Char? = null
        for (ch in text) {
            if (ch == prevCh) {
                run++
                if (run >= maxRepeats) continue
            } else {
                run = 0; prevCh = ch
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * True when the cleaned text carries no real information — empty, or a single repeated
     * exclamation. Such windows are dropped rather than stored as a "transcript".
     */
    fun isJunk(text: String): Boolean {
        val t = text.trim().trim('!', '?', '.', ',', ' ', '\n')
        if (t.isEmpty()) return true
        return t.length <= 2 && t.all { !it.isLetterOrDigit() }
    }
}

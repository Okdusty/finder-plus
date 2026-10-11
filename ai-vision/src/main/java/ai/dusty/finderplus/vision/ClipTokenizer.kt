package ai.dusty.finderplus.vision

import java.io.File

/**
 * CLIP's byte-pair-encoding tokenizer, compatible with OpenAI CLIP and OpenCLIP (and therefore with
 * MobileCLIP2, which ships an OpenCLIP-format text tower).
 *
 * This is the piece whose absence made the text tower return zeros. It matters far beyond text search:
 * a working text tower turns *any* label string into a vector in the same space as the image
 * embeddings, which is what lets the app recognize things it was never taught — see
 * [ZeroShotClassifier] — and lets a brand-new personal label start from a sensible prior instead of
 * from nothing.
 *
 * The vocabulary is loaded from the model directory rather than bundled, so the base APK stays small.
 */
class ClipTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<String, Int>,
    private val sot: Int,
    private val eot: Int,
) {

    private val cache = HashMap<String, List<String>>()

    /**
     * Encode to a fixed [CONTEXT]-length id sequence: `<|startoftext|> … <|endoftext|>`, zero-padded.
     * Over-long text is truncated with the end-of-text token forced into the last slot, because the
     * text tower reads the embedding at the EOT position — losing it yields a garbage vector.
     */
    fun encode(text: String): IntArray {
        val clean = WHITESPACE.replace(text, " ").trim().lowercase()
        val ids = ArrayList<Int>(CONTEXT)
        ids += sot
        for (match in PATTERN.findAll(clean)) {
            val piece = match.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { BYTE_ENCODER[it.toInt() and 0xFF].toString() }
            for (token in bpeOf(piece)) encoder[token]?.let { ids += it }
        }
        ids += eot

        val out = IntArray(CONTEXT)
        if (ids.size > CONTEXT) {
            for (i in 0 until CONTEXT) out[i] = ids[i]
            out[CONTEXT - 1] = eot
        } else {
            for (i in ids.indices) out[i] = ids[i]
        }
        return out
    }

    private fun bpeOf(token: String): List<String> = cache.getOrPut(token) { bpe(token) }

    /** Greedy merge of the lowest-ranked adjacent pair until no known pair remains. */
    private fun bpe(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        var word = ArrayList<String>(token.length)
        for (c in token) word += c.toString()
        word[word.size - 1] = word[word.size - 1] + END_OF_WORD
        if (word.size == 1) return word

        while (true) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until word.size - 1) {
                val rank = bpeRanks[word[i] + " " + word[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break

            val first = word[bestIndex]
            val second = word[bestIndex + 1]
            val next = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    next += first + second
                    i += 2
                } else {
                    next += word[i]
                    i++
                }
            }
            word = next
            if (word.size == 1) break
        }
        return word
    }

    companion object {
        /** CLIP's fixed text context. The tower's positional embedding is sized exactly for this. */
        const val CONTEXT = 77

        private const val END_OF_WORD = "</w>"
        private const val SOT_TOKEN = "<|startoftext|>"
        private const val EOT_TOKEN = "<|endoftext|>"

        private val WHITESPACE = Regex("\\s+")

        /** OpenAI's contraction-aware split. `\p{L}`/`\p{N}` keep non-Latin scripts intact. */
        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|\p{L}+|\p{N}|[^\s\p{L}\p{N}]+"""
        )

        /**
         * Reversible byte→printable-char map. BPE operates on text, but inputs are arbitrary UTF-8
         * bytes; mapping the 68 control/space bytes into an unused printable range keeps every byte
         * representable without introducing characters the merge table would split on.
         */
        private val BYTE_ENCODER: Map<Int, Char> = buildByteEncoder()

        private fun buildByteEncoder(): Map<Int, Char> {
            val bs = ArrayList<Int>(256)
            for (i in '!'.code..'~'.code) bs += i
            for (i in '¡'.code..'¬'.code) bs += i
            for (i in '®'.code..'ÿ'.code) bs += i
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            return bs.indices.associate { bs[it] to cs[it].toChar() }
        }

        /**
         * Load from the HuggingFace pair `vocab.json` + `merges.txt`. Returns null when either file is
         * missing or malformed — callers degrade to "text leg unavailable" rather than encoding into a
         * wrong space, which would look plausible while being meaningless.
         */
        fun load(vocabJson: File, mergesTxt: File): ClipTokenizer? {
            if (!vocabJson.isFile || !mergesTxt.isFile) return null
            return try {
                val encoder = parseVocab(vocabJson.readText())

                val ranks = HashMap<String, Int>(50_000)
                var rank = 0
                mergesTxt.useLines { lines ->
                    for (line in lines) {
                        val t = line.trim()
                        // The first line is a `#version:` header in HF exports.
                        if (t.isEmpty() || t.startsWith("#")) continue
                        if (t.indexOf(' ') <= 0) continue
                        ranks[t] = rank++
                    }
                }
                val sot = encoder[SOT_TOKEN] ?: return null
                val eot = encoder[EOT_TOKEN] ?: return null
                if (ranks.isEmpty()) return null
                ClipTokenizer(encoder, ranks, sot, eot)
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Parse CLIP's `vocab.json`, which is always a flat `{"token": id}` object.
         *
         * Hand-rolled rather than via `org.json` because that class is a stub on the JVM, and a
         * tokenizer whose correctness cannot be unit-tested off-device is a tokenizer whose subtle
         * breakage surfaces as quietly-bad search results months later.
         */
        internal fun parseVocab(text: String): Map<String, Int> {
            val out = HashMap<String, Int>(50_000)
            var i = text.indexOf('{')
            if (i < 0) return out
            i++
            val key = StringBuilder()
            while (i < text.length) {
                when {
                    text[i] == '}' -> return out
                    text[i].isWhitespace() || text[i] == ',' -> i++
                    text[i] == '"' -> {
                        key.setLength(0)
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                i++
                                when (val e = text[i]) {
                                    'n' -> key.append('\n')
                                    'r' -> key.append('\r')
                                    't' -> key.append('\t')
                                    'b' -> key.append('\b')
                                    'f' -> key.append('')
                                    'u' -> {
                                        key.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                                        i += 4
                                    }
                                    else -> key.append(e) // \" \\ \/
                                }
                            } else {
                                key.append(text[i])
                            }
                            i++
                        }
                        i++ // closing quote
                        while (i < text.length && (text[i].isWhitespace() || text[i] == ':')) i++
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '-')) i++
                        if (i > start) out[key.toString()] = text.substring(start, i).toInt()
                    }
                    else -> i++
                }
            }
            return out
        }
    }
}

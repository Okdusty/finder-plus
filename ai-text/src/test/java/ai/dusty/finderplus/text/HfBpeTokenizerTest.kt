package ai.dusty.finderplus.text

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.StringReader
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A near-miss tokenization still produces a unit vector, just a worse one, so tokenizer bugs fail
 * silently. The merge mechanics are pinned here on tiny hand-built vocabularies, and
 * [matchesReferenceTokenizer] checks a real tokenizer token-for-token against Hugging Face
 * `tokenizers` when the reference files are supplied.
 */
class HfBpeTokenizerTest {

    private val tiny = """
        {
          "added_tokens": [
            {"id": 0, "content": "<pad>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true},
            {"id": 1, "content": "<eos>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true},
            {"id": 2, "content": "<bos>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true}
          ],
          "normalizer": {"type": "Replace", "pattern": {"String": " "}, "content": "▁"},
          "pre_tokenizer": {"type": "Split", "pattern": {"String": " "}, "behavior": "MergedWithPrevious", "invert": false},
          "post_processor": {"type": "TemplateProcessing",
            "single": [{"SpecialToken": {"id": "<bos>", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "<eos>", "type_id": 0}}],
            "special_tokens": {}},
          "model": {
            "type": "BPE", "dropout": null, "unk_token": "<unk>", "continuing_subword_prefix": null,
            "end_of_word_suffix": null, "fuse_unk": true, "byte_fallback": true, "ignore_merges": false,
            "vocab": {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "a": 4, "b": 5, "c": 6, "ab": 7, "bc": 8,
                      "abc": 9, "▁": 10, "▁a": 11, "aa": 12, "<0xC3>": 13, "<0xA9>": 14, "\"": 15, "\\": 16},
            "merges": [["a", "b"], ["b", "c"], ["ab", "c"], ["▁", "a"], ["a", "a"]]
          }
        }
    """.trimIndent()

    private val tok = HfBpeTokenizer.load(StringReader(tiny))

    @Test fun mergesFollowRankNotPosition() {
        // (a,b) outranks (b,c), so "abc" is ab+c → abc, never a+bc.
        assertThat(tok.encode("abc").toList()).containsExactly(2, 9, 1).inOrder()
        assertThat(tok.encode("bc").toList()).containsExactly(2, 8, 1).inOrder()
    }

    @Test fun equalRanksMergeLeftmostFirst() {
        assertThat(tok.encode("aaa").toList()).containsExactly(2, 12, 4, 1).inOrder()
    }

    @Test fun spacesBecomeMetaspaceBeforeMerging() {
        assertThat(tok.encode(" a").toList()).containsExactly(2, 11, 1).inOrder()
        assertThat(tok.encode("a a").toList()).containsExactly(2, 4, 11, 1).inOrder()
    }

    @Test fun unknownCharactersFallBackToBytes() {
        assertThat(tok.encode("é").toList()).containsExactly(2, 13, 14, 1).inOrder()
    }

    @Test fun charactersWithNoBytePiecesFuseIntoOneUnk() {
        assertThat(tok.encode("xyz").toList()).containsExactly(2, 3, 1).inOrder()
    }

    @Test fun specialTokensInTextAreKeptWhole() {
        assertThat(tok.encode("a<bos>b").toList()).containsExactly(2, 4, 2, 5, 1).inOrder()
    }

    @Test fun escapedJsonKeysRoundTrip() {
        assertThat(tok.encode("\"\\").toList()).containsExactly(2, 15, 16, 1).inOrder()
    }

    @Test fun truncationKeepsTheTemplate() {
        val ids = tok.encode("ababababab", maxLength = 4)
        assertThat(ids.toList()).containsExactly(2, 7, 7, 1).inOrder()
    }

    private val byteLevel = """
        {
          "added_tokens": [
            {"id": 0, "content": "<s>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": true, "special": true},
            {"id": 1, "content": "</s>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": true, "special": true},
            {"id": 2, "content": "<mask>", "single_word": false, "lstrip": true, "rstrip": false, "normalized": false, "special": true}
          ],
          "normalizer": null,
          "pre_tokenizer": {"type": "ByteLevel", "add_prefix_space": false, "trim_offsets": true, "use_regex": true},
          "post_processor": {"type": "RobertaProcessing", "sep": ["</s>", 1], "cls": ["<s>", 0], "trim_offsets": true, "add_prefix_space": false},
          "decoder": {"type": "ByteLevel", "add_prefix_space": true, "trim_offsets": true, "use_regex": true},
          "model": {
            "type": "BPE", "dropout": null, "unk_token": null, "continuing_subword_prefix": "", "end_of_word_suffix": "",
            "fuse_unk": false, "byte_fallback": false, "ignore_merges": false,
            "vocab": {"<s>": 0, "</s>": 1, "<mask>": 2, "a": 3, "b": 4, "Ġ": 5, "Ġa": 6, "ab": 7, "Ã": 8, "©": 9, "Ã©": 10},
            "merges": ["Ġ a", "a b", "Ã ©"]
          }
        }
    """.trimIndent()

    @Test fun byteLevelMapsSpacesAndBytesToSymbols() {
        val t = HfBpeTokenizer.load(StringReader(byteLevel))
        // " a" is one pre-token whose space becomes Ġ; "é" is two UTF-8 bytes merged back into one symbol.
        assertThat(t.encode("ab a é").toList()).containsExactly(0, 7, 6, 5, 10, 1).inOrder()
        assertThat(t.decode(intArrayOf(0, 7, 6, 5, 10, 1), skipSpecial = true)).isEqualTo("ab a é")
    }

    @Test fun lstripTokensTakeTheWhitespaceBeforeThem() {
        val t = HfBpeTokenizer.load(StringReader(byteLevel))
        assertThat(t.encode("a <mask>").toList()).containsExactly(0, 3, 2, 1).inOrder()
    }

    @Test fun unsupportedConfigurationsAreRefused() {
        val regex = tiny.replace("""{"String": " "}, "content"""", """{"Regex": "\\s"}, "content"""")
        val thrown = runCatching { HfBpeTokenizer.load(StringReader(regex)) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    /**
     * Token-for-token parity with Hugging Face `tokenizers` on a real vocabulary.
     *
     * Opt-in because tokenizers are large downloads. Generate the reference with `tokenizers` and run with:
     *
     *     FINDER_TOKENIZER_JSON=/path/tokenizer.json FINDER_TOKENIZER_REF=/path/ref.jsonl \
     *       ./gradlew :ai-text:testDebugUnitTest
     *
     * where each line of the reference is `{"text": "...", "ids": [...]}`, or for byte-level tokenizers
     * also `{"decode": [...], "decoded": "..."}`.
     */
    @Test fun matchesReferenceTokenizer() {
        val json = System.getenv("FINDER_TOKENIZER_JSON")?.let(::File)
        val ref = System.getenv("FINDER_TOKENIZER_REF")?.let(::File)
        assumeTrue(json?.isFile == true && ref?.isFile == true)

        val cache = java.io.File.createTempFile("tok", HfBpeTokenizer.CACHE_SUFFIX).also { it.delete() }
        val t0 = System.nanoTime()
        val parsed = HfBpeTokenizer.load(json!!, cache)
        println("parsed ${parsed.vocabSize} tokens in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertThat(cache.isFile).isTrue()
        val t1 = System.nanoTime()
        // The second load must come from the compact cache and tokenize identically.
        val real = HfBpeTokenizer.load(json, cache)
        println("cached load in ${(System.nanoTime() - t1) / 1_000_000} ms")
        assertThat(real.vocabSize).isEqualTo(parsed.vocabSize)
        cache.deleteOnExit()

        var cases = 0
        val failures = ArrayList<String>()
        ref!!.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val p = JsonPull(StringReader(line))
            var text = ""
            val ids = ArrayList<Int>()
            var decoded: String? = null
            p.beginObject()
            while (p.hasNext()) when (p.nextName()) {
                "text" -> text = p.nextString()
                "ids", "decode" -> { p.beginArray(); while (p.hasNext()) ids += p.nextInt(); p.endArray() }
                "decoded" -> decoded = p.nextString()
                else -> p.skipValue()
            }
            p.endObject()
            cases++
            if (decoded != null) {
                val got = real.decode(ids.toIntArray())
                if (got != decoded) failures += "decode $ids → expected ${decoded.take(40)} got ${got.take(40)}"
                return@forEachLine
            }
            val got = real.encode(text).toList()
            if (got != ids) failures += "${text.take(40)} → expected $ids got $got"
            if (parsed.encode(text).toList() != ids) failures += "(json) ${text.take(40)}"
        }
        println("$cases reference cases, ${failures.size} mismatches")
        assertThat(failures).isEmpty()
        assertThat(cases).isGreaterThan(0)
    }
}

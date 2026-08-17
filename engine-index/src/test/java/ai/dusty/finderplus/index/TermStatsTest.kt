package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins that keyword filtering is decided by corpus statistics rather than by a word list.
 *
 * The behaviour being protected: nothing in this file names a language, yet Turkish and English function
 * words are both suppressed. That is the property a stoplist cannot have — it only covers the languages
 * someone remembered to write down, and this gallery mixes two inside single screenshots.
 */
class TermStatsTest {

    /** Builds a snapshot the way [TermStats.rebuild] would, from documents rather than declarations. */
    private fun snapshotOf(docs: List<String>, labels: List<Pair<Long, String>> = emptyList()): TermStats.Snapshot {
        val textDf = HashMap<String, Int>()
        var n = 0
        for (d in docs) {
            val terms = TermStats.tokenize(d).toHashSet()
            if (terms.isEmpty()) continue
            n++
            for (t in terms) textDf[t] = (textDf[t] ?: 0) + 1
        }
        val labelDf = HashMap<String, Int>()
        for ((_, l) in labels) labelDf[l.lowercase()] = (labelDf[l.lowercase()] ?: 0) + 1
        return TermStats.Snapshot(textDf, labelDf, n, labels.map { it.first }.distinct().size)
    }

    /** A letters-only token distinct per index, since the tokenizer discards digits. */
    private fun uniqueWord(i: Int): String = "uniq" + i.toString().map { 'a' + (it - '0') }.joinToString("")

    /** A corpus where two languages' function words are equally ubiquitous. */
    private fun bilingualCorpus(): TermStats.Snapshot {
        val docs = ArrayList<String>()
        // 100 documents, every one containing the function words of both languages plus one unique noun.
        // The noun must be letters-only: the tokenizer drops digits, so `unique1`..`unique99` would all
        // collapse to the single term `unique` present in every document — the opposite of unique.
        for (i in 0 until 100) {
            docs += "the and you for this ve bir bu için daha ${uniqueWord(i)}"
        }
        // A term appearing in 2 of 100 documents is under the ceiling and must survive.
        docs[0] += " kargoya"
        docs[1] += " kargoya"
        return snapshotOf(docs)
    }

    @Test fun ubiquitousWordsAreSuppressedInEveryLanguageAtOnce() {
        val s = bilingualCorpus()
        for (english in listOf("the", "and", "you", "for", "this")) {
            assertThat(s.isCommonText(english)).isTrue()
        }
        for (turkish in listOf("ve", "bir", "bu", "için", "daha")) {
            assertThat(s.isCommonText(turkish)).isTrue()
        }
    }

    @Test fun rareWordsSurviveRegardlessOfLanguage() {
        val s = bilingualCorpus()
        assertThat(s.isCommonText("kargoya")).isFalse()        // 2 of 100 documents
        assertThat(s.isCommonText(uniqueWord(7))).isFalse()    // 1 of 100
    }

    @Test fun keywordsRankTheDistinctiveTermsAboveTheCommonOnes() {
        val s = bilingualCorpus()
        val picked = s.keywordsFor("the and you for this ve bir bu için kargoya", limit = 3)
        // Only the rare term clears the ceiling, so it is the sole keyword rather than merely the first.
        assertThat(picked).containsExactly("kargoya")
    }

    @Test fun aSmallCorpusSuppressesNothing() {
        // With few documents a frequency is noise: in three documents, a term in one sits at 33%.
        // Suppressing on that basis would strip a new install of the only words it has.
        val s = snapshotOf(listOf("the cat", "the dog", "the bird"))
        assertThat(s.documents).isLessThan(TermStats.MIN_CORPUS)
        assertThat(s.isCommonText("the")).isFalse()
        assertThat(s.keywordsFor("the cat", limit = 5)).contains("cat")
    }

    @Test fun tokenizerKeepsNonAsciiWordsWhole() {
        // The reason for \p{L} over \w: the latter splits Turkish letters mid-word, so `için` would
        // become `i` + `in` and never match anything a person typed.
        assertThat(TermStats.tokenize("için şey ğüöç")).containsExactly("için", "şey", "ğüöç")
        // Digits carry no meaning out of context — timestamps, prices, unread counts.
        assertThat(TermStats.tokenize("2026 15:30 abc")).containsExactly("abc")
    }

    @Test fun theRarityFloorIsOffByDefaultAndKeepsOneOffTerms() {
        // Measured: enabling it drops 49% of keyword tags and leaves 17% of items with none, while the
        // OCR garbage it targets survives anyway because misreads of a recurring interface recur too.
        assertThat(TermStats.MIN_DOC_FREQUENCY).isEqualTo(1)
        val s = bilingualCorpus()
        assertThat(s.isTooRare(uniqueWord(7))).isFalse()   // appears in exactly one document
        assertThat(s.keywordsFor("the and ${uniqueWord(7)}", limit = 5)).contains(uniqueWord(7))
    }

    @Test fun anUncountedTermIsKeptRatherThanTreatedAsRare() {
        // New OCR documents appear after the last count; "not counted yet" is not evidence of rarity.
        // Failing closed here would strip every keyword from whatever was indexed most recently.
        val s = bilingualCorpus()
        assertThat(s.isTooRare("neverseenbefore")).isFalse()
    }

    @Test fun labelsUseTheirOwnHigherCeiling() {
        // A label the gallery genuinely contains often is legitimate; only near-universal ones are noise.
        val onEveryItem = (1L..100L).map { it to "screenshot" }
        val onAFew = (1L..100L).map { it to "screenshot" } + (1L..20L).map { it to "food" }
        assertThat(snapshotOf(listOf(), onEveryItem).isCommonLabel("screenshot")).isTrue()
        assertThat(snapshotOf(listOf(), onAFew).isCommonLabel("food")).isFalse()
    }
}

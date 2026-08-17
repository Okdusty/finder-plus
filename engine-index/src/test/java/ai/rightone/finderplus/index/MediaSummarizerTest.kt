package ai.rightone.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaSummarizerTest {

    @Test fun frequentVideoLabelsOutrankStrayDetections() {
        // A video pass emits the same label once per frame; a one-off detection is noise.
        val e = MediaEvidence(
            labels = List(14) { "dog" } + listOf("sofa", "lamp"),
            objects = listOf("dog", "dog", "cushion"),
        )
        val s = MediaSummarizer.summarize(e)
        assertThat(s.keyObjects.first()).isEqualTo("dog")
        assertThat(s.summary).contains("Shows dog")
    }

    @Test fun uninformativeLabelsAreDroppedByCorpusFrequencyNotByAWordList() {
        // This used to assert a hard-coded English set (`flesh`, `close-up`, `font`). The set is gone: it
        // could only ever cover the languages someone enumerated, and it encoded a guess about which
        // labels are uninformative *in general* when the useful question is which are uninformative in
        // THIS gallery. The caller now supplies that judgement from measured document frequency.
        val ubiquitous = setOf("flesh", "close-up", "font")
        val e = MediaEvidence(labels = listOf("flesh", "close-up", "font", "bicycle"))
        val s = MediaSummarizer.summarize(e, commonLabels = MediaSummarizer.CommonLabels { it in ubiquitous })
        assertThat(s.keyObjects).containsExactly("bicycle")
    }

    @Test fun withNoCorpusYetNothingIsSuppressed() {
        // The honest default before the gallery has been counted: keep everything. Suppressing on
        // unmeasured assumptions is what produced `Eyelash` on 395 photos in the first place.
        val e = MediaEvidence(labels = listOf("flesh", "bicycle"))
        assertThat(MediaSummarizer.summarize(e).keyObjects).containsExactly("bicycle", "flesh")
    }

    @Test fun theUsersOwnLabelsAreNeverSuppressedHoweverOftenTheyUseThem() {
        // Someone who labels half their gallery `work` means it every time. Filtering a term *because*
        // they use it often would punish exactly the labelling the pipeline is trying to learn from.
        val e = MediaEvidence(userLabels = listOf("work"), labels = listOf("desk"))
        val s = MediaSummarizer.summarize(e, commonLabels = MediaSummarizer.CommonLabels { it == "work" })
        assertThat(s.keyObjects).contains("work")
        assertThat(s.summary).contains("Labelled work")
    }

    @Test fun theSummaryNamesDistinctiveWordsRatherThanQuotingATextPrefix() {
        // Quoting the first 300 characters duplicated the profile's own text section, and a prefix is
        // arbitrary — the informative words are wherever they fall.
        val e = MediaEvidence(
            ocrText = "the and for this is a very long screenshot about kargoya teslimat",
            textKeywords = listOf("kargoya", "teslimat"),
        )
        val s = MediaSummarizer.summarize(e)
        assertThat(s.summary).contains("Mentions kargoya, teslimat")
        assertThat(s.summary).doesNotContain("Text on screen")
    }

    @Test fun withoutKeywordsTheSummaryStillQuotesTheText() {
        // A freshly-installed index has no corpus counts yet; it must still say something.
        val e = MediaEvidence(ocrText = "boarding pass Istanbul")
        assertThat(MediaSummarizer.summarize(e).summary).contains("Text on screen: boarding pass Istanbul")
    }

    @Test fun objectsWeighMoreThanWholeImageLabels() {
        val e = MediaEvidence(labels = listOf("room"), objects = listOf("guitar"))
        assertThat(MediaSummarizer.summarize(e).keyObjects.first()).isEqualTo("guitar")
    }

    @Test fun namedPeopleBeatAnonymousFaceCounts() {
        val named = MediaSummarizer.summarize(MediaEvidence(peopleNames = listOf("Ayşe"), faceCount = 2))
        assertThat(named.summary).contains("With Ayşe")
        val anon = MediaSummarizer.summarize(MediaEvidence(faceCount = 3))
        assertThat(anon.summary).contains("3 people")
    }

    @Test fun transcriptAndOcrAreIncludedButBounded() {
        val e = MediaEvidence(transcript = "x".repeat(1000), ocrText = "y".repeat(1000))
        val s = MediaSummarizer.summarize(e)
        assertThat(s.summary).contains("Says:")
        assertThat(s.summary).contains("Text on screen:")
        // Neither source may dominate the profile.
        assertThat(s.summary.length).isLessThan(900)
    }

    @Test fun userLabelsLeadTheSummaryAndOutrankModelLabels() {
        val e = MediaEvidence(
            userLabels = listOf("dad's kitchen"),
            concepts = listOf("cooking"),
            labels = listOf("food", "kitchen"),
        )
        val s = MediaSummarizer.summarize(e)
        // The person's own words come first, and outweigh every model-produced term in the ranking.
        assertThat(s.summary).startsWith("Labelled dad's kitchen.")
        assertThat(s.keyObjects.first()).isEqualTo("dad's kitchen")
        assertThat(s.keyObjects).contains("cooking")
    }

    @Test fun captureTimeBecomesSearchableWords() {
        // 2026-03-12 was a Thursday; 19:30 local is "evening", March is spring.
        val cal = java.util.Calendar.getInstance().apply { set(2026, 2, 12, 19, 30, 0) }
        val s = MediaSummarizer.summarize(MediaEvidence(dateTakenMs = cal.timeInMillis))
        assertThat(s.summary).contains("2026")
        assertThat(s.summary).contains("March")
        assertThat(s.summary).contains("Thursday")
        assertThat(s.summary).contains("evening")
        assertThat(s.summary).contains("spring")
    }

    @Test fun implausibleCaptureTimeProducesNoDateWords() {
        // A zero/epoch timestamp is a missing value, not a 1970 photo.
        assertThat(MediaSummarizer.whenClause(0L)).isNull()
        assertThat(MediaSummarizer.whenClause(null)).isNull()
        assertThat(MediaSummarizer.whenClause(1_000L)).isNull()
    }

    @Test fun aspectRatioIdentifiesAPanorama() {
        val s = MediaSummarizer.summarize(MediaEvidence(width = 8000, height = 2000))
        assertThat(s.summary).contains("panorama")
    }

    @Test fun emptyEvidenceProducesEmptySummary() {
        assertThat(MediaSummarizer.summarize(MediaEvidence()).summary).isEmpty()
    }
}

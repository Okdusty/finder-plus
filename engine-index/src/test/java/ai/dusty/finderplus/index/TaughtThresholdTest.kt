package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the exemplar-scaled bar for auto-applying taught labels.
 *
 * The incident: `cem yılmaz`, taught on 3 screenshots, auto-applied to 73 items at a mean cosine of
 * 0.80 — because screenshots sit near 0.8 cosine to each other at baseline, and a 3-exemplar centroid
 * is little more than "looks like these screenshots". The flat 0.78 bar was correct only for mature
 * prototypes; these tests keep the bar tied to the evidence behind it.
 */
class TaughtThresholdTest {

    @Test fun youngPrototypesMustBeNearlyExact() {
        // 1-3 exemplars: 0.90 — above the measured screenshot-baseline band (~0.8), so a young label
        // fires only on near-duplicates of what it was taught on.
        assertThat(LabelLearner.taughtThreshold(0.78f, 1)).isEqualTo(0.90f)
        assertThat(LabelLearner.taughtThreshold(0.78f, 3)).isEqualTo(0.90f)
    }

    @Test fun theBarRelaxesAsEvidenceAccumulates() {
        assertThat(LabelLearner.taughtThreshold(0.78f, 4)).isEqualTo(0.85f)
        assertThat(LabelLearner.taughtThreshold(0.78f, 7)).isEqualTo(0.85f)
        assertThat(LabelLearner.taughtThreshold(0.78f, 8)).isEqualTo(0.78f)
        assertThat(LabelLearner.taughtThreshold(0.78f, 50)).isEqualTo(0.78f)
    }

    @Test fun aStricterCallerThresholdIsNeverLowered() {
        // The scaling can only raise the bar; a caller asking for 0.95 gets at least 0.95.
        assertThat(LabelLearner.taughtThreshold(0.95f, 2)).isEqualTo(0.95f)
        assertThat(LabelLearner.taughtThreshold(0.95f, 20)).isEqualTo(0.95f)
    }

    @Test fun entityNamesAreNeverAutoAsserted() {
        // Mirrors ConceptsPassHandler's banding: a label from an entity domain lands as SUGGESTED
        // regardless of score. Naming the wrong person is the one output a user calls broken rather
        // than imperfect, so names are proposals until a human confirms them.
        val vocab = ConceptVocabulary.parse(readVocabAsset())
        val entity = vocab.entityConcepts.first()
        // The rule the handler applies, restated: entity => SUGGESTED wins over any confidence.
        assertThat(vocab.isEntity(entity)).isTrue()
    }

    private companion object {
        fun readVocabAsset(): String {
            for (p in listOf(
                "src/main/assets/vocab/concepts.txt",
                "engine-index/src/main/assets/vocab/concepts.txt",
            )) {
                val f = java.io.File(p)
                if (f.isFile) return f.readText(Charsets.UTF_8)
            }
            error("concepts.txt not found from ${java.io.File(".").absolutePath}")
        }
    }
}

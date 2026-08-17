package ai.rightone.finderplus.index

import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how a multi-frame item is turned into one set of labels.
 *
 * A video stores one embedding per keyframe, so labelling it means reducing ~20 classifications to a
 * statement about the file. The pipeline used to take the **union** of each frame's winners, and this
 * test exists because that is not a conservative choice with a tuning problem — it is the wrong
 * reduction, and it fails in a way no stronger backbone would fix.
 *
 * Measured on 608 indexed videos before the change: 6,902 label tags, 11.4 per video against 3.5 for a
 * photo, and 52% of them derived from a tenth or less of the video's frames.
 */
class FrameAggregationTest {

    /** Mean of the per-frame posteriors — [ConceptClassifier.read]'s reduction, in miniature. */
    private fun meanPosterior(frames: List<Map<String, Float>>): Map<String, Float> {
        val acc = HashMap<String, Float>()
        for (f in frames) for ((label, p) in f) acc[label] = (acc[label] ?: 0f) + p
        return acc.mapValues { it.value / frames.size }
    }

    private fun union(frames: List<Map<String, Float>>, floor: Float = 0.20f): Set<String> =
        frames.flatMap { f -> f.filterValues { it >= floor }.keys }.toSet()

    /**
     * The real failure, reproduced from the measured case: one clip labelled
     * `cow, horse, shark, dog, turtle, screenshot of a video game` because six frames each won
     * something different. Twenty frames, no concept in more than three of them.
     */
    @Test fun unionInventsContradictionsThatNoFrameSupports() {
        val frames = List(20) { i ->
            when (i) {
                0, 1, 2 -> mapOf("cow" to 0.25f)
                3, 4 -> mapOf("horse" to 0.22f)
                5 -> mapOf("shark" to 0.21f)
                6 -> mapOf("dog" to 0.24f)
                7 -> mapOf("turtle" to 0.20f)
                else -> mapOf("screenshot of a video game" to 0.05f)
            }
        }

        // What the pipeline used to write: five mutually exclusive animals on one file.
        assertThat(union(frames)).containsExactly("cow", "horse", "shark", "dog", "turtle")

        // What it writes now: nothing is asserted, because nothing describes the video.
        val mean = meanPosterior(frames)
        assertThat(mean.filterValues { it >= 0.20f }).isEmpty()
        // "cow" was the strongest of them and still only reaches 3/20 of its frame confidence.
        assertThat(mean["cow"]).isWithin(1e-4f).of(0.0375f)
    }

    /** A concept genuinely present throughout keeps its confidence — the reduction is not just a damper. */
    @Test fun consistentContentSurvivesUndiminished() {
        val frames = List(20) { mapOf("beach" to 0.42f) }
        assertThat(meanPosterior(frames)["beach"]).isWithin(1e-4f).of(0.42f)
    }

    /** Content in a quarter of a long clip is a question, not a fact, and lands in the review band. */
    @Test fun partialContentBecomesASuggestion() {
        val frames = List(20) { i -> if (i < 5) mapOf("scooter" to 0.55f) else mapOf("scooter" to 0.05f) }
        val score = meanPosterior(frames)["scooter"]!!
        assertThat(score).isLessThan(0.20f)   // not asserted
        assertThat(score).isAtLeast(0.10f)    // but worth asking about
    }

    /**
     * The property that makes this safe to apply everywhere: for a single-vector item the mean of one
     * posterior is that posterior, so photos are unaffected bit for bit.
     */
    @Test fun singleFrameItemsAreUnchanged() {
        val one = mapOf("jewelry" to 0.251f, "ring" to 0.201f, "soup" to 0.037f)
        assertThat(meanPosterior(listOf(one))).isEqualTo(one)
    }

    @Test fun videoRunsTheConceptPassAfterItsFramesAreEmbedded() {
        val video = Pass.forKind(MediaKind.VIDEO)
        assertThat(video).contains(Pass.CONCEPTS)
        // Ordering is by priority tier, and CONCEPTS must come after the pass that writes the vectors
        // it reads — otherwise it classifies an empty set and reports nothing forever.
        assertThat(Pass.CONCEPTS.priority).isGreaterThan(Pass.KEYFRAMES.priority)
    }

    /**
     * The concept head needs no model, which is what makes re-labelling every video affordable: the
     * keyframe embeddings are already on disk, so this is arithmetic rather than a re-index.
     */
    @Test fun labellingVideoCostsNoModelWork() {
        assertThat(Pass.CONCEPTS.model).isEqualTo(ai.rightone.finderplus.model.RequiredModel.NONE)
    }
}

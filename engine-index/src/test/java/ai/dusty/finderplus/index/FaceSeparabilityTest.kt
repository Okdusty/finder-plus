package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the guard that stopped face clustering shipping a false-merge machine.
 *
 * With CLIP face crops, the most similar pairs tend to be *same-photo* pairs — provably different people
 * who happen to share lighting, camera and scene. Every threshold selective enough to be useful is
 * dominated by those pairs, so no threshold produces identity clusters at all.
 *
 * The guard turns that observation into a self-calibrating test: same-photo pairs are free labelled
 * negatives, and if they dominate the matches then the space encodes the scene, not the person.
 */
class FaceSeparabilityTest {

    /** Mirrors SimilarityClusterer.separates so the decision rule cannot drift unnoticed. */
    private fun separates(abovePairs: Int, samePhotoPairs: Int): Boolean {
        if (abovePairs < 20) return false
        return samePhotoPairs.toFloat() / abovePairs < 0.15f
    }

    @Test fun clipFaceCropsAreRejected() {
        // The CLIP pattern: at a useful threshold, most of the few surviving pairs are same-photo.
        assertThat(separates(abovePairs = 3, samePhotoPairs = 2)).isFalse()
    }

    @Test fun tooLittleEvidenceIsTreatedAsUntrustworthy() {
        // "Cannot verify" must behave like "do not trust", never like "probably fine" — a handful of
        // clean pairs is not evidence that the space separates identity.
        assertThat(separates(abovePairs = 19, samePhotoPairs = 0)).isFalse()
    }

    @Test fun mobileFaceNetPasses() {
        // A margin-trained face embedder: many pairs above threshold, only a small share same-photo,
        // an order of magnitude better than CLIP crops and comfortably inside the bound.
        assertThat(separates(abovePairs = 1067, samePhotoPairs = 93)).isTrue()
    }

    @Test fun aSpaceThatMostlyMergesStrangersIsRejected() {
        assertThat(separates(abovePairs = 100, samePhotoPairs = 20)).isFalse()
    }
}

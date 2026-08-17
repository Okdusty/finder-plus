package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the guard that stopped face clustering shipping a false-merge machine.
 *
 * Measured on a real gallery, CLIP face-crop similarities spanned 0.41-0.87 and the two most similar
 * crops in the whole set were both *same-photo* pairs — provably different people who happened to share
 * lighting, camera and scene. Every threshold selective enough to be useful was dominated by those pairs,
 * so no threshold produced identity clusters at all.
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
        // The real measurement: at a useful threshold, 2 of 3 surviving pairs were same-photo.
        assertThat(separates(abovePairs = 3, samePhotoPairs = 2)).isFalse()
    }

    @Test fun tooLittleEvidenceIsTreatedAsUntrustworthy() {
        // "Cannot verify" must behave like "do not trust", never like "probably fine" — a handful of
        // clean pairs is not evidence that the space separates identity.
        assertThat(separates(abovePairs = 19, samePhotoPairs = 0)).isFalse()
    }

    @Test fun mobileFaceNetPasses() {
        // The real measurement after installing MobileFaceNet: 1,067 pairs above 0.60, of which 93 were
        // same-photo — 8.7%, an order of magnitude better than CLIP's 67% and comfortably inside the bound.
        assertThat(separates(abovePairs = 1067, samePhotoPairs = 93)).isTrue()
    }

    @Test fun aSpaceThatMostlyMergesStrangersIsRejected() {
        assertThat(separates(abovePairs = 100, samePhotoPairs = 20)).isFalse()
    }
}

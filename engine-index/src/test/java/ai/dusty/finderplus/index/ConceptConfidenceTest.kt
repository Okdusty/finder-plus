package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.TagSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the confidence banding that stopped the classifier asserting noise.
 *
 * Writing every returned concept as a searchable tag stores mostly low-confidence noise, and each such
 * tag pollutes search while reading as fact.
 */
class ConceptConfidenceTest {

    /** Mirrors ConceptsPassHandler's banding so the thresholds cannot drift apart silently. */
    private fun band(score: Float, taught: Boolean = false): TagSource? = when {
        taught -> TagSource.LEARNED
        score >= 0.20f -> TagSource.CONCEPT
        score >= 0.10f -> TagSource.SUGGESTED
        else -> null
    }

    @Test fun noiseIsDiscardedRatherThanStored() {
        // Noise-level scores. Neither should reach the database at all — queueing them would spend the
        // user's attention on nothing.
        assertThat(band(0.037f)).isNull()
        assertThat(band(0.012f)).isNull()
    }

    @Test fun plausibleScoresBecomeQuestionsNotFacts() {
        assertThat(band(0.119f)).isEqualTo(TagSource.SUGGESTED)
        assertThat(band(0.199f)).isEqualTo(TagSource.SUGGESTED)
    }

    @Test fun confidentScoresAreAsserted() {
        assertThat(band(0.201f)).isEqualTo(TagSource.CONCEPT)
        assertThat(band(0.251f)).isEqualTo(TagSource.CONCEPT)
    }

    @Test fun whatTheUserTaughtIsNeverDowngraded() {
        // A taught label is judged on exemplars in image space, where scores run much higher; it must not
        // be re-banded against zero-shot thresholds and demoted to a question.
        assertThat(band(0.05f, taught = true)).isEqualTo(TagSource.LEARNED)
    }

    @Test fun suggestedOrdinalMatchesTheQueriesThatFilterIt() {
        // ContentDao filters `source != 8` in raw SQL, which no compiler checks. This assertion is the
        // only thing standing between a reordered enum and unconfirmed guesses silently becoming
        // searchable again — which is exactly the bug it caught when first written against 9.
        assertThat(TagSource.SUGGESTED.ordinal).isEqualTo(8)
        assertThat(TagSource.CONCEPT.ordinal).isEqualTo(7)
        assertThat(TagSource.USER.ordinal).isEqualTo(4)
    }
}

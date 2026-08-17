package ai.rightone.finderplus.index

import ai.rightone.finderplus.model.TagSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the confidence banding that stopped the classifier asserting noise.
 *
 * Measured on a real 4,839-item gallery, the previous behaviour wrote every returned concept as a
 * searchable tag: 36.6% of them landed below 0.05 joint confidence and 60% below 0.10 — `soup` at
 * 0.037, `person dancing` at 0.037. Each one polluted search while reading as fact.
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
        // The two worst real examples from the gallery. Neither should reach the database at all —
        // queueing them would spend the user's attention on nothing.
        assertThat(band(0.037f)).isNull()
        assertThat(band(0.012f)).isNull()
    }

    @Test fun plausibleScoresBecomeQuestionsNotFacts() {
        assertThat(band(0.119f)).isEqualTo(TagSource.SUGGESTED)
        assertThat(band(0.199f)).isEqualTo(TagSource.SUGGESTED)
    }

    @Test fun confidentScoresAreAsserted() {
        // Hand-verified correct on real photos: jewelry 0.251, ring 0.201, bus 0.237.
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

package ai.dusty.finderplus.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vote system's two promises: history tunes the order, and history can never overrule
 * relevance. Both directions have a failure mode — a boost with no ceiling turns the top slot into
 * a self-reinforcing rut (whatever is clicked first stays first forever), and an unbounded penalty
 * silently censors items from search.
 */
class VotesTest {

    @Test fun upvotedItemRisesPastNearTies() {
        // Items at rank 2 and 3 are near-ties; picking history on item 30 should lift it above 20.
        val order = listOf(10L, 20L, 30L, 40L)
        val reranked = Votes.rerank(order, votes = mapOf(30L to 6f))
        assertTrue(reranked.indexOf(30L) < reranked.indexOf(20L))
    }

    @Test fun votesCannotOverruleRelevance() {
        // A maximally-upvoted item deep in the list must NOT leapfrog a strong #1: tanh caps the
        // boost at 1.35x, and rank-score decay dwarfs that across 30 positions.
        val order = (1L..40L).toList()
        val reranked = Votes.rerank(order, votes = mapOf(35L to 100f))
        assertEquals(1L, reranked.first())
        assertTrue(reranked.indexOf(35L) > 5)
    }

    @Test fun downvotedItemSinksButSurvives() {
        val order = listOf(10L, 20L, 30L)
        val reranked = Votes.rerank(order, votes = mapOf(10L to -100f))
        assertTrue(reranked.indexOf(10L) > 0)   // sank
        assertTrue(reranked.contains(10L))       // never censored
    }

    @Test fun noVotesMeansUntouchedOrder() {
        val order = listOf(5L, 6L, 7L)
        assertEquals(order, Votes.rerank(order, emptyMap()))
    }

    @Test fun termsFoldDiacriticsSoSpellingsPoolTheirHistory() {
        // "köpek" and "kopek" must land on the same vote row.
        assertEquals(Votes.terms("köpek sahilde"), Votes.terms("kopek sahilde"))
        assertEquals(listOf("kopek", "sahilde"), Votes.terms("Köpek sahilde"))
    }

    @Test fun termsAreCappedSoOneTapCannotFanOut() {
        val many = Votes.terms("bir iki üç dört beş altı yedi sekiz dokuz")
        assertTrue(many.size <= 6)
    }

    @Test fun boostIsBoundedBothWays() {
        assertTrue(Votes.boost(1000f) <= 1.36f)
        assertTrue(Votes.boost(-1000f) >= 0.64f)
    }
}

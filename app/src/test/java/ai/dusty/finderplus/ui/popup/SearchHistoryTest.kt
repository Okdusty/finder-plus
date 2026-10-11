package ai.dusty.finderplus.ui.popup

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pop-up's suggestions are the user's own searches; these pin how they are ranked. */
class SearchHistoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private val t0 = 1_800_000_000_000L

    private fun history() = SearchHistory(tmp.root.resolve("history.tsv"))

    @Test fun previousSearchComesFirstThenMostFrequent() {
        val h = history()
        repeat(5) { h.record("invoice", t0 + it) }
        repeat(2) { h.record("beach", t0 + 10 + it) }
        h.record("wedding", t0 + 100) // the latest, used once
        assertThat(h.rank(10, t0 + 200).map { it.query }).containsExactly("wedding", "invoice", "beach").inOrder()
    }

    @Test fun caseAndSpacingDoNotSplitAQuery() {
        val h = history()
        h.record("Dog  at the Beach", t0)
        h.record("dog at the beach", t0 + 1)
        val ranked = h.rank(10, t0 + 2)
        assertThat(ranked).hasSize(1)
        assertThat(ranked[0].count).isEqualTo(2)
        assertThat(ranked[0].query).isEqualTo("dog at the beach") // latest spelling shown
    }

    @Test fun oldHabitsFade() {
        val h = history()
        repeat(4) { h.record("old favourite", t0) }           // four uses, 120 days ago
        repeat(2) { h.record("this week", t0 + 118 * day) }   // two uses, recent
        h.record("latest", t0 + 120 * day)
        val ranked = h.rank(10, t0 + 120 * day).map { it.query }
        assertThat(ranked.indexOf("this week")).isLessThan(ranked.indexOf("old favourite"))
    }

    @Test fun tooShortQueriesAreIgnored() {
        val h = history()
        h.record(" a ", t0)
        assertThat(h.rank(10, t0)).isEmpty()
    }

    @Test fun removedEntriesStayRemovedAcrossRestarts() {
        val h = history()
        h.record("receipt", t0); h.record("beach", t0 + 1)
        h.remove("RECEIPT")
        val reopened = history()
        assertThat(reopened.rank(10, t0 + 2).map { it.query }).containsExactly("beach")
    }

    @Test fun historySurvivesARestart() {
        history().apply { repeat(3) { record("cat", t0 + it) } }
        val back = history().rank(10, t0 + 10)
        assertThat(back.single().count).isEqualTo(3)
    }
}

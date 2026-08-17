package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the governor's rule: **spend the budget, then stop** — never "refuse to start what cannot all
 * finish". The first release projected `median × remaining` against the budget and parked at the
 * warmup boundary: exactly 15 captions ran on a 4,876-item gallery and 4,861 were skipped with 74
 * minutes of budget unspent. These tests make that regression loud.
 */
class CaptionBudgetTest {

    @Test fun keepsSpendingPastWarmupWhenTheWholeGalleryCannotFit() {
        val b = CaptionBudget()
        // 4.4 s/item median, 5,000 remaining — nowhere near fitting the budget in one run.
        repeat(CaptionBudget.WARMUP + 5) {
            assertThat(b.shouldContinue(remaining = 5_000)).isTrue()
            b.record(4_400)
        }
    }

    @Test fun parksOnlyOnceTheBudgetIsActuallySpent() {
        val b = CaptionBudget()
        var spent = 0L
        var captioned = 0
        while (b.shouldContinue(remaining = 5_000)) {
            b.record(4_400); spent += 4_400; captioned++
            if (spent > CaptionBudget.TOTAL_BUDGET_MS + 10_000) break // safety, must not be reached
        }
        // ~75 min / 4.4 s ≈ a thousand items — the run does real work before parking.
        assertThat(spent).isAtLeast(CaptionBudget.TOTAL_BUDGET_MS)
        assertThat(captioned).isAtLeast(1_000)
    }

    @Test fun threeConsecutiveSlowCaptionsDisable() {
        val b = CaptionBudget()
        repeat(CaptionBudget.MAX_STRIKES) { b.record(CaptionBudget.PER_ITEM_CEILING_MS + 1) }
        assertThat(b.shouldContinue(remaining = 10)).isFalse()
    }

    @Test fun aFastCaptionResetsTheStrikeCount() {
        val b = CaptionBudget()
        b.record(CaptionBudget.PER_ITEM_CEILING_MS + 1)
        b.record(CaptionBudget.PER_ITEM_CEILING_MS + 1)
        b.record(2_000) // contention cleared — not a broken device
        b.record(CaptionBudget.PER_ITEM_CEILING_MS + 1)
        assertThat(b.shouldContinue(remaining = 10)).isTrue()
    }

    @Test fun resetGrantsAFreshRunItsFullAllowance() {
        val b = CaptionBudget()
        repeat(CaptionBudget.MAX_STRIKES) { b.record(CaptionBudget.PER_ITEM_CEILING_MS + 1) }
        assertThat(b.shouldContinue(remaining = 10)).isFalse()
        b.reset()
        assertThat(b.shouldContinue(remaining = 10)).isTrue()
    }
}

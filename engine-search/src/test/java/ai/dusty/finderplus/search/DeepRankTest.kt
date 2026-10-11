package ai.dusty.finderplus.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeepRankTest {

    @Test fun theFirstNumberInTheReplyIsTheScore() {
        assertThat(DeepRank.parseScore("7")).isEqualTo(0.7f)
        assertThat(DeepRank.parseScore("Score: 8.5 out of 10")).isEqualTo(0.85f)
        assertThat(DeepRank.parseScore("12")).isEqualTo(1f)
        assertThat(DeepRank.parseScore("no idea")).isNull()
        assertThat(DeepRank.parseScore(null)).isNull()
    }

    @Test fun onlyCheckedResultsMoveAndOnlyAmongTheirOwnPlaces() {
        // 3 was not checked, so it keeps the third place.
        val order = DeepRank.order(listOf(1L, 2L, 3L, 4L), mapOf(1L to 0.2f, 2L to 0.9f, 4L to 0.5f))
        assertThat(order).containsExactly(2L, 4L, 3L, 1L).inOrder()
    }

    @Test fun resultsBelowTheLineAreLeftOut() {
        val order = DeepRank.order(listOf(1L, 2L, 3L), mapOf(1L to 0.1f, 2L to 0.8f), dropBelow = 0.3f)
        assertThat(order).containsExactly(2L, 3L).inOrder()
    }

    @Test fun equalScoresKeepTheirOrder() {
        assertThat(DeepRank.order(listOf(5L, 6L), mapOf(5L to 0.5f, 6L to 0.5f))).containsExactly(5L, 6L).inOrder()
    }
}

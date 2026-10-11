package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The names are placeholders: the arithmetic depends only on order and membership. */
class GroupRewardsTest {

    @Test fun theFirstChoiceGetsTheMostAndUnchosenNothing() {
        val s = GroupRewards.of(listOf("a", "b", "c", "d"), ranked = listOf("c", "a")).associateBy { it.label }
        assertThat(s.getValue("c").reward).isEqualTo(1f)
        assertThat(s.getValue("a").reward).isEqualTo(0.5f)
        assertThat(s.getValue("b").reward).isEqualTo(0f)
        assertThat(s.getValue("c").rank).isEqualTo(1)
        assertThat(s.getValue("b").rank).isNull()
    }

    @Test fun advantagesAreRelativeToTheGroup() {
        val s = GroupRewards.of(listOf("a", "b", "c"), ranked = listOf("a"))
        assertThat(s.sumOf { it.advantage.toDouble() }).isWithin(1e-5).of(0.0)
        assertThat(s.first { it.label == "a" }.advantage).isGreaterThan(0f)
        assertThat(s.filter { it.label != "a" }.all { it.advantage < 0f }).isTrue()
    }

    @Test fun aGroupWhereEveryNameScoresTheSameTeachesNothing() {
        assertThat(GroupRewards.of(listOf("a", "b"), ranked = emptyList()).map { it.advantage }).containsExactly(0f, 0f)
        assertThat(GroupRewards.of(listOf("a"), ranked = listOf("a")).single().advantage).isEqualTo(0f)
    }
}

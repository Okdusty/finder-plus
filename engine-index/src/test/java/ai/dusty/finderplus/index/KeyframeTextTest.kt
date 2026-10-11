package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyframeTextTest {

    @Test fun aLineOnScreenForManyFramesCountsOnce() {
        val frames = listOf("Title\nfirst", "title\nsecond", "TITLE\nfirst")
        assertThat(KeyframeText.collapse(frames)).isEqualTo("Title\nfirst\nsecond")
    }

    @Test fun framesWithoutTextGiveNothing() {
        assertThat(KeyframeText.collapse(listOf("", "  \n "))).isNull()
    }
}

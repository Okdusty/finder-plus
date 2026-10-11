package ai.dusty.finderplus.index

import ai.dusty.finderplus.db.vector.Vecs
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaMomentsTest {

    private fun v(vararg x: Float) = Vecs.normalized(x)

    @Test fun theTypicalFrameIsTheOneMostLikeTheRest() {
        // Most of the video is one scene; a single frame of another scene comes first.
        val frames = listOf(
            0L to v(0f, 1f, 0f),
            1_000L to v(1f, 0f, 0.1f),
            2_000L to v(1f, 0f, -0.1f),
            3_000L to v(1f, 0f, 0f),
        )
        assertThat(MediaMoments.typical(frames)).isEqualTo(3_000L)
    }

    @Test fun noFramesHaveNoTypicalOne() {
        assertThat(MediaMoments.typical(emptyList())).isNull()
    }
}

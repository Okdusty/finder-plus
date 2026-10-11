package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TranscriptWindowsTest {

    private fun seg(startMs: Long, text: String) = Segment(1L, startMs, startMs + 5_000, text)

    @Test fun eachWindowStartsAtItsFirstSegment() {
        val w = TranscriptWindows.of(
            listOf(seg(2_000, "a"), seg(30_000, "b"), seg(65_000, "c"), seg(90_000, "d"), seg(130_000, "e")),
            windowMs = 60_000,
        )
        assertThat(w.map { it.first }).containsExactly(2_000L, 65_000L, 130_000L).inOrder()
        assertThat(w.map { it.second }).containsExactly("a b", "c d", "e").inOrder()
    }

    @Test fun noSegmentsNoWindows() {
        assertThat(TranscriptWindows.of(emptyList())).isEmpty()
    }
}

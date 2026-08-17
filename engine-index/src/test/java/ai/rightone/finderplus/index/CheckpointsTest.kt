package ai.rightone.finderplus.index

import ai.rightone.finderplus.index.work.Checkpoint
import ai.rightone.finderplus.index.work.Checkpoints
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The checkpoint codec is what a resumed process reads to continue mid-file, so its round-trip must
 * be exact. See docs/design/01-DB-ENGINE.md §4.2, §5.
 */
class CheckpointsTest {

    @Test fun transcribeRoundTrips() {
        val cp = Checkpoint.Transcribe(nextChunkStartMs = 1_830_000, lang = "tr")
        val decoded = Checkpoints.decode(Checkpoints.encode(cp))
        assertThat(decoded).isEqualTo(cp)
    }

    @Test fun transcribeWithNullLangRoundTrips() {
        val cp = Checkpoint.Transcribe(nextChunkStartMs = 0, lang = null)
        val decoded = Checkpoints.decode(Checkpoints.encode(cp))
        assertThat(decoded).isEqualTo(cp)
    }

    @Test fun keyframesRoundTrips() {
        val cp = Checkpoint.Keyframes(nextFrameIndex = 7, totalFrames = 20)
        val decoded = Checkpoints.decode(Checkpoints.encode(cp))
        assertThat(decoded).isEqualTo(cp)
    }

    @Test fun noneEncodesToNull() {
        assertThat(Checkpoints.encode(Checkpoint.None)).isNull()
        assertThat(Checkpoints.decode(null)).isEqualTo(Checkpoint.None)
        assertThat(Checkpoints.decode("")).isEqualTo(Checkpoint.None)
    }

    @Test fun garbageDecodesToNone() {
        assertThat(Checkpoints.decode("ZZZ|bogus")).isEqualTo(Checkpoint.None)
    }
}

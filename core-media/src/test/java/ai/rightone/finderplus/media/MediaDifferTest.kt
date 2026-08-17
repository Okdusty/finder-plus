package ai.rightone.finderplus.media

import ai.rightone.finderplus.model.MediaKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaDifferTest {

    private fun live(id: Long, dm: Long, gen: Long = 0L, size: Long = 100L) =
        MediaDigest(id, MediaKind.IMAGE, dm, gen, size)

    private fun known(id: Long, dm: Long, gen: Long = 0L, size: Long = 100L) =
        IndexedSignature(id, dm, gen, size)

    @Test fun detectsNewFiles() {
        val diff = MediaDiffer.diff(live = listOf(live(1, 10), live(2, 10)), known = listOf(known(1, 10)))
        assertThat(diff.added.map { it.id }).containsExactly(2L)
        assertThat(diff.changed).isEmpty()
        assertThat(diff.removedIds).isEmpty()
    }

    @Test fun detectsModifiedByDate() {
        val diff = MediaDiffer.diff(live = listOf(live(1, 20)), known = listOf(known(1, 10)))
        assertThat(diff.changed.map { it.id }).containsExactly(1L)
    }

    @Test fun detectsModifiedByGeneration() {
        val diff = MediaDiffer.diff(live = listOf(live(1, 10, gen = 5)), known = listOf(known(1, 10, gen = 4)))
        assertThat(diff.changed.map { it.id }).containsExactly(1L)
    }

    @Test fun detectsModifiedBySize() {
        val diff = MediaDiffer.diff(live = listOf(live(1, 10, size = 200)), known = listOf(known(1, 10, size = 100)))
        assertThat(diff.changed.map { it.id }).containsExactly(1L)
    }

    @Test fun detectsRemoved() {
        val diff = MediaDiffer.diff(live = emptyList(), known = listOf(known(1, 10), known(2, 10)))
        assertThat(diff.removedIds).containsExactly(1L, 2L)
    }

    @Test fun unchangedProducesEmptyDiff() {
        val diff = MediaDiffer.diff(live = listOf(live(1, 10)), known = listOf(known(1, 10)))
        assertThat(diff.isEmpty).isTrue()
    }

    /**
     * Regression: an unknown (0) stored generation must NOT look like a change. When it did, every
     * scan purged and re-indexed the whole gallery, discarding all completed AI work — which is why
     * indexing never advanced past ~1,000 of 19,657 units on device.
     */
    @Test fun unknownStoredGenerationIsNotAChange() {
        val diff = MediaDiffer.diff(
            live = listOf(live(1, 10, gen = 987_654)),
            known = listOf(known(1, 10, gen = 0)),
        )
        assertThat(diff.changed).isEmpty()
        assertThat(diff.isEmpty).isTrue()
    }

    @Test fun unknownLiveGenerationIsNotAChange() {
        val diff = MediaDiffer.diff(
            live = listOf(live(1, 10, gen = 0)),
            known = listOf(known(1, 10, gen = 987_654)),
        )
        assertThat(diff.changed).isEmpty()
    }

    @Test fun realGenerationBumpIsStillDetected() {
        val diff = MediaDiffer.diff(
            live = listOf(live(1, 10, gen = 1_002)),
            known = listOf(known(1, 10, gen = 1_001)),
        )
        assertThat(diff.changed.map { it.id }).containsExactly(1L)
    }
}

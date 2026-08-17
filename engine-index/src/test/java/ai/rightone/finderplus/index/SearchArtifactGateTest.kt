package ai.rightone.finderplus.index

import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins when an item becomes keyword-searchable.
 *
 * These exist because the failure was invisible: every pass completed, every row was written, and the
 * FTS table stayed empty. On a live index that meant 4,847 items with 2,427 OCR documents and 27,496 tags
 * committed, and **zero** profiles — nothing findable by keyword at all, with no error anywhere.
 */
class SearchArtifactGateTest {

    /** Passes the finalization gate waits for, mirroring `remainingCheapTextPassesForItem`'s SQL. */
    private val gated = setOf(
        Pass.METADATA, Pass.IMAGE_LABEL, Pass.OCR, Pass.KEYFRAMES, Pass.OBJECTS, Pass.FACES,
    ).map { it.ordinal }.toSet()

    @Test fun theGateNeverWaitsOnAPassFromALaterTier() {
        // The bug: CONCEPTS was in the gate. It cannot run until every keyframe in the gallery is
        // embedded, so waiting for it turned "is this item done" into "is the whole gallery done".
        val cheapest = gated.map { Pass.entries[it].priority }.max()
        for (ordinal in gated) {
            assertThat(Pass.entries[ordinal].priority).isAtMost(cheapest)
        }
        assertThat(gated).doesNotContain(Pass.CONCEPTS.ordinal)
        assertThat(gated).doesNotContain(Pass.TRANSCRIBE.ordinal)
        assertThat(Pass.CONCEPTS.priority).isGreaterThan(cheapest)
        assertThat(Pass.TRANSCRIBE.priority).isGreaterThan(cheapest)
    }

    @Test fun everyKindHasAtLeastOneGatedPassSoFinalizationIsReachable() {
        // A kind whose passes are all outside the gate would finalize on its first completion, before
        // its text existed. A kind with none reachable would never finalize at all.
        for (kind in MediaKind.entries) {
            val enqueued = Pass.forKind(kind).map { it.ordinal }
            assertThat(enqueued.any { it in gated }).isTrue()
        }
    }

    @Test fun laterPassesStillRefreshTheArtifactsTheyContributeTo() {
        // Excluded from the *gate*, but they do produce text, so their completion must trigger a rebuild.
        assertThat(Pass.CONCEPTS.contributesText).isTrue()
        assertThat(Pass.TRANSCRIBE.contributesText).isTrue()
    }

    @Test fun vectorOnlyPassesDoNotTriggerRebuilds() {
        // IMAGE_EMBED is the most-run pass in the gallery and changes no text; rebuilding after it would
        // be pure waste.
        assertThat(Pass.IMAGE_EMBED.contributesText).isFalse()
        assertThat(Pass.TEXT_EMBED.contributesText).isFalse()
    }

    @Test fun retiredPassesAreNotEnqueuedForAnyKind() {
        val enqueued = MediaKind.entries.flatMap { Pass.forKind(it) }.toSet()
        // Retired for pollution or for having no working encoder; the enum entries survive only because
        // ordinals are persisted. OBJECTS is deliberately NOT here any more — it returned in v2 backed
        // by YOLOX's 80 concrete classes, replacing the ML Kit buckets that got it retired.
        assertThat(enqueued).doesNotContain(Pass.IMAGE_LABEL)
        assertThat(enqueued).doesNotContain(Pass.TEXT_EMBED)
        assertThat(enqueued).contains(Pass.OBJECTS)
        assertThat(Pass.OBJECTS.version).isAtLeast(2)
    }

    @Test fun captionRunsAfterConceptsAndBeforeTranscription() {
        // The caption is the cheap descriptive win; ASR is the 10-hour tail. A video must become
        // findable by what it looks like before its transcript's turn comes up.
        assertThat(Pass.CAPTION.priority).isGreaterThan(Pass.CONCEPTS.priority)
        assertThat(Pass.CAPTION.priority).isLessThan(Pass.TRANSCRIBE.priority)
        assertThat(Pass.forKind(MediaKind.IMAGE)).contains(Pass.CAPTION)
        assertThat(Pass.forKind(MediaKind.VIDEO)).contains(Pass.CAPTION)
        // Audio has no frame to describe.
        assertThat(Pass.forKind(MediaKind.AUDIO)).doesNotContain(Pass.CAPTION)
    }

    @Test fun conceptsIsTheOnlyRemainingSourceOfLabels() {
        // With the ML Kit passes retired, nothing else writes a label, so CONCEPTS must run on every
        // kind that can carry one.
        assertThat(Pass.forKind(MediaKind.IMAGE)).contains(Pass.CONCEPTS)
        assertThat(Pass.forKind(MediaKind.VIDEO)).contains(Pass.CONCEPTS)
    }
}

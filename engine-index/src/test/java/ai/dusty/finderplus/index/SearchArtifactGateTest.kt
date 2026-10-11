package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.Pass
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins when an item becomes keyword-searchable. A wrong gate fails silently: every pass completes and
 * every row is written, but no profile is built, so nothing is findable by keyword.
 */
class SearchArtifactGateTest {

    /** Passes the finalization gate waits for, mirroring `remainingCheapTextPassesForItem`'s SQL. */
    private val gated = setOf(
        Pass.METADATA, Pass.IMAGE_LABEL, Pass.OCR, Pass.KEYFRAMES, Pass.FACES,
    ).map { it.ordinal }.toSet()

    @Test fun theGateNeverWaitsOnAPassFromALaterTier() {
        // CONCEPTS cannot run until every item is embedded, so gating on it would turn "is this item
        // done" into "is everything done".
        val cheapest = gated.map { Pass.entries[it].priority }.max()
        for (ordinal in gated) {
            assertThat(Pass.entries[ordinal].priority).isAtMost(cheapest)
        }
        assertThat(gated).doesNotContain(Pass.CONCEPTS.ordinal)
        assertThat(gated).doesNotContain(Pass.TRANSCRIBE.ordinal)
        assertThat(gated).doesNotContain(Pass.OBJECTS.ordinal)
        assertThat(Pass.CONCEPTS.priority).isGreaterThan(cheapest)
        assertThat(Pass.OBJECTS.priority).isGreaterThan(cheapest)
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
        // IMAGE_EMBED runs on nearly every item and changes no text; rebuilding after it is waste.
        assertThat(Pass.IMAGE_EMBED.contributesText).isFalse()
        assertThat(Pass.TEXT_EMBED.contributesText).isFalse()
    }

    @Test fun retiredPassesAreNotEnqueuedForAnyKind() {
        val enqueued = MediaKind.entries.flatMap { Pass.forKind(it) }.toSet()
        // IMAGE_LABEL is retired; its enum entry stays only because ordinals are persisted. OBJECTS
        // (the describe model) and TEXT_EMBED (EmbeddingGemma 2) are enqueued from version 2 on.
        assertThat(enqueued).doesNotContain(Pass.IMAGE_LABEL)
        assertThat(enqueued).contains(Pass.OBJECTS)
        assertThat(Pass.OBJECTS.version).isAtLeast(2)
        assertThat(enqueued).contains(Pass.TEXT_EMBED)
        assertThat(Pass.TEXT_EMBED.version).isAtLeast(2)
    }

    @Test fun textEmbeddingRunsAfterEveryPassItReads() {
        // It embeds the profile and transcript, so every text producer must come first.
        for (kind in MediaKind.entries) {
            val passes = Pass.forKind(kind)
            assertThat(passes).contains(Pass.TEXT_EMBED)
            val producers = passes.filter { it.contributesText }
            assertThat(producers.all { it.priority < Pass.TEXT_EMBED.priority }).isTrue()
        }
    }

    @Test fun describingRunsAfterTheCheapPassesAndBeforeTranscription() {
        // The describe model is the costliest pass per photo; OCR and faces make an item searchable
        // first. A video should still be findable by what it looks like before its transcript's turn.
        assertThat(Pass.OBJECTS.priority).isGreaterThan(Pass.CONCEPTS.priority)
        assertThat(Pass.OBJECTS.priority).isLessThan(Pass.TRANSCRIBE.priority)
        assertThat(Pass.forKind(MediaKind.IMAGE)).contains(Pass.OBJECTS)
        assertThat(Pass.forKind(MediaKind.VIDEO)).contains(Pass.OBJECTS)
        // Audio has no frame to describe.
        assertThat(Pass.forKind(MediaKind.AUDIO)).doesNotContain(Pass.OBJECTS)
    }

    @Test fun retiredPassesAreNotEnqueued() {
        assertThat(Pass.retired).containsAtLeast(Pass.CAPTION, Pass.IMAGE_LABEL)
        for (kind in MediaKind.entries) assertThat(Pass.forKind(kind)).containsNoneIn(Pass.retired)
    }

    @Test fun conceptsIsTheOnlyRemainingSourceOfLabels() {
        // With the ML Kit passes retired, nothing else writes a label, so CONCEPTS must run on every
        // kind that can carry one.
        assertThat(Pass.forKind(MediaKind.IMAGE)).contains(Pass.CONCEPTS)
        assertThat(Pass.forKind(MediaKind.VIDEO)).contains(Pass.CONCEPTS)
    }
}

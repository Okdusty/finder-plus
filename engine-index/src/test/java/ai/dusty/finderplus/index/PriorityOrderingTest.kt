package ai.rightone.finderplus.index

import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.Pass
import ai.rightone.finderplus.model.RequiredModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the invariant whose violation stalled a whole-gallery re-embed at exactly zero progress:
 * model affinity is a tie-break *within* a priority band, never a substitute for priority.
 */
class PriorityOrderingTest {

    @Test fun imageEmbedOutranksTranscribe() {
        // The starvation case: ASR resident, ~1,200 transcriptions queued at ~30 s each, embeddings
        // waiting behind all of them because affinity was sorted ahead of priority.
        assertThat(Pass.IMAGE_EMBED.priority).isLessThan(Pass.TRANSCRIBE.priority)
    }

    @Test fun conceptsShareTheEmbedTierSoLabelsAppearProgressively() {
        // Same tier, ordered within it by (item_id, id): each item is embedded and then immediately
        // labelled. A later tier would be correct in dependency terms but wrong in practice — no
        // concept tag would exist until every embedding in the gallery had finished.
        assertThat(Pass.CONCEPTS.priority).isEqualTo(Pass.IMAGE_EMBED.priority)
    }

    @Test fun conceptsAreEnqueuedAfterTheEmbeddingWithinAnItem() {
        // The tie-break is work_unit id, which follows this enqueue order, so the dependency holds.
        val image = Pass.forKind(MediaKind.IMAGE)
        assertThat(image.indexOf(Pass.CONCEPTS)).isGreaterThan(image.indexOf(Pass.IMAGE_EMBED))
    }

    @Test fun conceptsNeedNoHeavyModel() {
        // Pure arithmetic over stored rows, so it must never contend for the residency lock.
        assertThat(Pass.CONCEPTS.model).isEqualTo(RequiredModel.NONE)
    }

    @Test fun imagePipelineEmbedsBeforeItLabels() {
        val image = Pass.forKind(MediaKind.IMAGE)
        assertThat(image).containsAtLeast(Pass.IMAGE_EMBED, Pass.CONCEPTS).inOrder()
    }

    @Test fun encoderSwapBumpedTheEmbeddingPassVersion() {
        // ViT-B/32 and ViT-B/16 vectors are both 512-d and silently incomparable, so the only thing
        // that forces a re-embed is this version number.
        assertThat(Pass.IMAGE_EMBED.version).isAtLeast(3)
    }
}

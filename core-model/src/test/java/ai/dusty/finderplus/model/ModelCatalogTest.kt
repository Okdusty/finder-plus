package ai.dusty.finderplus.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The catalog is the contract for safe installs: every file is fetched over HTTPS and refused unless
 * it matches its SHA-256. These checks stop an edit from introducing a floating URL, a missing
 * checksum, or a companion that never gets installed.
 */
class ModelCatalogTest {

    private val downloadable = ModelCatalog.all.filter { it.url.isNotEmpty() }

    @Test fun idsAreUnique() {
        assertThat(ModelCatalog.all.map { it.id }).containsNoDuplicates()
    }

    @Test fun everyCompanionResolvesWithoutCycles() {
        for (spec in ModelCatalog.all) {
            spec.requiresId?.let { assertWithMessage("${spec.id} requires a missing $it").that(ModelCatalog.byId(it)).isNotNull() }
            val chain = ModelCatalog.withCompanions(spec)
            assertThat(chain.last()).isEqualTo(spec)
            assertThat(chain.map { it.id }).containsNoDuplicates()
        }
    }

    @Test fun everyDownloadIsHttpsAndChecksummed() {
        for (spec in downloadable) {
            assertWithMessage("${spec.id} url").that(spec.url).startsWith("https://")
            assertWithMessage("${spec.id} sha256").that(spec.sha256).matches("[0-9a-f]{64}")
            assertWithMessage("${spec.id} size").that(spec.sizeBytes).isGreaterThan(0L)
        }
    }

    @Test fun huggingFaceDownloadsArePinnedToACommit() {
        // A checksum against a moving branch breaks the day upstream re-uploads; a commit never moves.
        for (spec in downloadable.filter { "huggingface.co" in it.url }) {
            assertWithMessage(spec.id).that(spec.url).containsMatch("/resolve/[0-9a-f]{40}/")
        }
    }

    @Test fun onDiskNamesNeverCollide() {
        val named = ModelCatalog.all.mapNotNull { it.fileName }
        assertThat(named).containsNoDuplicates()
    }

    @Test fun embeddingGemmaInstallsAsOneUnit() {
        val chain = ModelCatalog.withCompanions(ModelCatalog.TEXT_EMBEDDER).map { it.id }
        assertThat(chain).containsExactly(
            ModelCatalog.TEXT_EMBEDDER_TOKENIZER.id, ModelCatalog.TEXT_EMBEDDER_WEIGHTS.id, ModelCatalog.TEXT_EMBEDDER.id,
        ).inOrder()
        // ONNX Runtime finds the external weights beside the graph by their exact upstream name.
        assertThat(ModelCatalog.TEXT_EMBEDDER.fileName!!.substringBeforeLast('/'))
            .isEqualTo(ModelCatalog.TEXT_EMBEDDER_WEIGHTS.fileName!!.substringBeforeLast('/'))
        assertThat(ModelCatalog.TEXT_EMBEDDER_WEIGHTS.fileName).endsWith("model_q4.onnx_data")
    }

    @Test fun visualSearchInstallsBothTowers() {
        val chain = ModelCatalog.withCompanions(ModelCatalog.CLIP_IMAGE).map { it.id }
        assertThat(chain).containsAtLeast(ModelCatalog.CLIP_TEXT.id, ModelCatalog.CLIP_VOCAB.id, ModelCatalog.CLIP_MERGES.id)
    }

    @Test fun companionsAreHiddenAndEveryUserFacingModelIsTiered() {
        val companions = ModelCatalog.companionIds
        assertThat(ModelCatalog.userFacing.map { it.id }).containsNoneIn(companions)
        assertThat(ModelCatalog.userFacing.count { it.tier == ModelTier.ESSENTIAL }).isAtLeast(1)
        assertThat(ModelCatalog.userFacing.map { it.id }).contains(ModelCatalog.TEXT_EMBEDDER.id)
    }

    @Test fun essentialsStaySmallEnoughForATap() {
        val bytes = ModelCatalog.userFacing.filter { it.tier == ModelTier.ESSENTIAL }.sumOf { ModelCatalog.bundleBytes(it) }
        assertThat(bytes).isLessThan(400_000_000L)
    }

    @Test fun everySpeechFallbackIsASpeechModel() {
        for (s in ModelCatalog.asrPreference) assertThat(s.role).isAnyOf(ModelRole.ASR, ModelRole.ASR_WHISPER)
    }
}

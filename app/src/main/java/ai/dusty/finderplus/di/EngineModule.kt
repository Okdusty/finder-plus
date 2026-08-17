package ai.dusty.finderplus.di

import android.content.Context
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.WorkLedger
import ai.dusty.finderplus.index.IndexEngine
import ai.dusty.finderplus.index.IndexOrchestrator
import ai.dusty.finderplus.index.IndexStatusListener
import ai.dusty.finderplus.index.ConceptVocabulary
import ai.dusty.finderplus.index.PowerPolicy
import ai.dusty.finderplus.media.FrameExtractor
import ai.dusty.finderplus.media.MediaStoreReader
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.speech.SpeechRecognizer
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.vision.ImageAnalyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides @Singleton
    fun powerPolicy(@ApplicationContext context: Context): PowerPolicy = PowerPolicy(context)

    /**
     * The open-set concept vocabulary, as **data**: a runtime override at `files/vocab/concepts.txt`
     * wins, else the bundled `assets/vocab/concepts.txt`. Parsed once (Hilt caches the singleton), so
     * the recognized concepts can be regionalised or replaced without a rebuild. An unreadable/empty
     * source yields an empty vocabulary, which the seed worker reports as "nothing seeded" rather than
     * crashing.
     */
    @Provides @Singleton
    fun conceptVocabulary(@ApplicationContext context: Context): ConceptVocabulary {
        val override = java.io.File(context.filesDir, "vocab/concepts.txt")
        val text = runCatching {
            if (override.isFile) override.readText(Charsets.UTF_8)
            else context.assets.open("vocab/concepts.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull().orEmpty()
        return ConceptVocabulary.parse(text)
    }

    @Provides @Singleton
    fun orchestrator(
        @ApplicationContext context: Context,
        db: FinderDatabase,
        reader: MediaStoreReader,
        frameExtractor: FrameExtractor,
        imageAnalyzer: ImageAnalyzer,
        objectDetector: ai.dusty.finderplus.vision.ObjectDetector,
        faceAnalyzer: ai.dusty.finderplus.vision.FaceAnalyzer,
        speechRecognizer: SpeechRecognizer,
        textEmbedder: TextEmbedder,
        workLedger: WorkLedger,
        conceptClassifier: ai.dusty.finderplus.index.ConceptClassifier,
        cpuBooster: ai.dusty.finderplus.index.CpuBooster,
        coordinator: ai.dusty.finderplus.index.ModelCoordinator,
        perfPrefs: ai.dusty.finderplus.index.PerfPrefs,
        vlmCaptioner: ai.dusty.finderplus.speech.VlmCaptioner,
        captionBudget: ai.dusty.finderplus.index.CaptionBudget,
        vaultPolicy: ai.dusty.finderplus.index.VaultPolicy,
        statusListener: IndexStatusListener,
    ): IndexOrchestrator = IndexEngine.create(
        context = context,
        db = db,
        reader = reader,
        frameExtractor = frameExtractor,
        imageAnalyzer = imageAnalyzer,
        objectDetector = objectDetector,
        faceAnalyzer = faceAnalyzer,
        speechRecognizer = speechRecognizer,
        textEmbedder = textEmbedder,
        workLedger = workLedger,
        clipModelId = ModelCatalog.CLIP_IMAGE.id,
        conceptClassifier = conceptClassifier,
        cpuBooster = cpuBooster,
        coordinator = coordinator,
        perfPrefs = perfPrefs,
        vlmCaptioner = vlmCaptioner,
        captionBudget = captionBudget,
        vaultPolicy = vaultPolicy,
        statusListener = statusListener,
    )
}

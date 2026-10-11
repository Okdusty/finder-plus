package ai.dusty.finderplus.index

import android.content.Context
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.WorkLedger
import ai.dusty.finderplus.index.pass.ImageEmbedPassHandler
import ai.dusty.finderplus.index.pass.ImageLabelPassHandler
import ai.dusty.finderplus.index.pass.KeyframesPassHandler
import ai.dusty.finderplus.index.pass.MetadataPassHandler
import ai.dusty.finderplus.index.pass.FacesPassHandler
import ai.dusty.finderplus.index.pass.ObjectsPassHandler
import ai.dusty.finderplus.index.pass.OcrPassHandler
import ai.dusty.finderplus.index.pass.PassHandler
import ai.dusty.finderplus.index.pass.TextEmbedPassHandler
import ai.dusty.finderplus.index.pass.TranscribePassHandler
import ai.dusty.finderplus.media.FrameExtractor
import ai.dusty.finderplus.media.MediaStoreReader
import ai.dusty.finderplus.speech.SpeechRecognizer
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.vision.ImageAnalyzer

/**
 * Assembles the orchestrator from public collaborators, keeping the scanner, finalizer, coordinator,
 * power policy and pass handlers internal to this module. One [ModelCoordinator] is shared by every
 * heavy pass so residency is enforced globally; one [DecodedImageCache] is shared by the image passes
 * so each photo is decoded once per item rather than once per pass.
 */
object IndexEngine {
    fun create(
        context: Context,
        db: FinderDatabase,
        reader: MediaStoreReader,
        frameExtractor: FrameExtractor,
        imageAnalyzer: ImageAnalyzer,
        describer: ai.dusty.finderplus.vision.ImageDescriber,
        clipText: ai.dusty.finderplus.vision.ClipTextEncoder,
        faceAnalyzer: ai.dusty.finderplus.vision.FaceAnalyzer,
        speechRecognizer: SpeechRecognizer,
        textEmbedder: TextEmbedder,
        workLedger: WorkLedger,
        clipModelId: String,
        conceptClassifier: ConceptClassifier,
        cpuBooster: CpuBooster,
        coordinator: ModelCoordinator,
        perfPrefs: PerfPrefs,
        vaultPolicy: VaultPolicy,
        statusListener: IndexStatusListener = IndexStatusListener.NoOp,
        /** Runs when an index run starts with a scan, before any pass; the app applies held edits here. */
        beforeRun: suspend () -> Unit = {},
    ): IndexOrchestrator {
        val imageCache = DecodedImageCache(context)
        val powerPolicy = PowerPolicy(context) { perfPrefs.unrestricted }
        val handlers: Set<PassHandler> = setOf(
            MetadataPassHandler(context),
            ImageLabelPassHandler(imageCache, imageAnalyzer),
            OcrPassHandler(imageCache, imageAnalyzer),
            ObjectsPassHandler(context, imageCache, db.embeddingDao(), describer, coordinator, DetectionReview(db, imageAnalyzer, clipText, coordinator)),
            FacesPassHandler(imageCache, faceAnalyzer),
            ImageEmbedPassHandler(imageCache, imageAnalyzer, coordinator, clipModelId),
            KeyframesPassHandler(frameExtractor, imageAnalyzer, coordinator, clipModelId),
            TranscribePassHandler(speechRecognizer, coordinator),
            TextEmbedPassHandler(db.contentDao(), db.mediaProfileDao(), textEmbedder, coordinator),
            ai.dusty.finderplus.index.pass.ConceptsPassHandler(conceptClassifier),
        )
        val termStats = TermStats(db)
        return DefaultIndexOrchestrator(
            db = db,
            scanner = Scanner(reader, db),
            ledger = workLedger,
            finalizer = ItemFinalizer(db, terms = termStats),
            terms = termStats,
            coordinator = coordinator,
            power = powerPolicy,
            imageCache = imageCache,
            booster = cpuBooster,
            vaultPolicy = vaultPolicy,
            appContext = context.applicationContext,
            statusListener = statusListener,
            frames = frameExtractor,
            passHandlers = handlers,
            beforeRun = beforeRun,
        )
    }
}

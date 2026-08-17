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
        objectDetector: ai.dusty.finderplus.vision.ObjectDetector,
        faceAnalyzer: ai.dusty.finderplus.vision.FaceAnalyzer,
        speechRecognizer: SpeechRecognizer,
        textEmbedder: TextEmbedder,
        workLedger: WorkLedger,
        clipModelId: String,
        conceptClassifier: ConceptClassifier,
        cpuBooster: CpuBooster,
        coordinator: ModelCoordinator,
        perfPrefs: PerfPrefs,
        vlmCaptioner: ai.dusty.finderplus.speech.VlmCaptioner,
        captionBudget: CaptionBudget,
        vaultPolicy: VaultPolicy,
        statusListener: IndexStatusListener = IndexStatusListener.NoOp,
    ): IndexOrchestrator {
        val imageCache = DecodedImageCache(context)
        val powerPolicy = PowerPolicy(context) { perfPrefs.unrestricted }
        val handlers: Set<PassHandler> = setOf(
            MetadataPassHandler(context),
            ImageLabelPassHandler(imageCache, imageAnalyzer),
            OcrPassHandler(imageCache, imageAnalyzer),
            ObjectsPassHandler(context, imageCache, objectDetector),
            FacesPassHandler(imageCache, faceAnalyzer),
            ImageEmbedPassHandler(imageCache, imageAnalyzer, coordinator, clipModelId),
            KeyframesPassHandler(frameExtractor, imageAnalyzer, coordinator, clipModelId),
            TranscribePassHandler(speechRecognizer, coordinator),
            TextEmbedPassHandler(db.contentDao(), textEmbedder, coordinator),
            ai.dusty.finderplus.index.pass.ConceptsPassHandler(conceptClassifier),
            ai.dusty.finderplus.index.pass.CaptionPassHandler(
                context, imageCache, vlmCaptioner, coordinator, captionBudget,
                remainingCaptions = { db.workUnitDao().pendingForPass(ai.dusty.finderplus.model.Pass.CAPTION.ordinal) },
            ),
        )
        val termStats = TermStats(db)
        return DefaultIndexOrchestrator(
            db = db,
            scanner = Scanner(reader, db),
            ledger = workLedger,
            finalizer = ItemFinalizer(db, terms = termStats),
            terms = termStats,
            captionBudget = captionBudget,
            coordinator = coordinator,
            power = powerPolicy,
            imageCache = imageCache,
            booster = cpuBooster,
            vaultPolicy = vaultPolicy,
            appContext = context.applicationContext,
            statusListener = statusListener,
            passHandlers = handlers,
        )
    }
}

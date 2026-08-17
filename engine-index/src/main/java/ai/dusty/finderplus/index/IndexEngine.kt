package ai.rightone.finderplus.index

import android.content.Context
import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.db.WorkLedger
import ai.rightone.finderplus.index.pass.ImageEmbedPassHandler
import ai.rightone.finderplus.index.pass.ImageLabelPassHandler
import ai.rightone.finderplus.index.pass.KeyframesPassHandler
import ai.rightone.finderplus.index.pass.MetadataPassHandler
import ai.rightone.finderplus.index.pass.FacesPassHandler
import ai.rightone.finderplus.index.pass.ObjectsPassHandler
import ai.rightone.finderplus.index.pass.OcrPassHandler
import ai.rightone.finderplus.index.pass.PassHandler
import ai.rightone.finderplus.index.pass.TextEmbedPassHandler
import ai.rightone.finderplus.index.pass.TranscribePassHandler
import ai.rightone.finderplus.media.FrameExtractor
import ai.rightone.finderplus.media.MediaStoreReader
import ai.rightone.finderplus.speech.SpeechRecognizer
import ai.rightone.finderplus.text.TextEmbedder
import ai.rightone.finderplus.vision.ImageAnalyzer

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
        objectDetector: ai.rightone.finderplus.vision.ObjectDetector,
        faceAnalyzer: ai.rightone.finderplus.vision.FaceAnalyzer,
        speechRecognizer: SpeechRecognizer,
        textEmbedder: TextEmbedder,
        workLedger: WorkLedger,
        clipModelId: String,
        conceptClassifier: ConceptClassifier,
        cpuBooster: CpuBooster,
        coordinator: ModelCoordinator,
        perfPrefs: PerfPrefs,
        vlmCaptioner: ai.rightone.finderplus.speech.VlmCaptioner,
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
            ai.rightone.finderplus.index.pass.ConceptsPassHandler(conceptClassifier),
            ai.rightone.finderplus.index.pass.CaptionPassHandler(
                context, imageCache, vlmCaptioner, coordinator, captionBudget,
                remainingCaptions = { db.workUnitDao().pendingForPass(ai.rightone.finderplus.model.Pass.CAPTION.ordinal) },
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

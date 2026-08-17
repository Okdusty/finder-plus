package ai.dusty.finderplus.di

import android.content.Context
import ai.dusty.finderplus.media.PcmDecoder
import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.speech.FileModelManager
import ai.dusty.finderplus.speech.ModelManager
import ai.dusty.finderplus.speech.ModelPrefs
import ai.dusty.finderplus.speech.Qwen3SpeechRecognizer
import ai.dusty.finderplus.speech.SpeechRecognizer
import ai.dusty.finderplus.vision.FaceAnalyzer
import ai.dusty.finderplus.vision.OnnxFaceDetector
import ai.dusty.finderplus.vision.ObjectDetector
import ai.dusty.finderplus.text.OnnxTextEmbedder
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.vision.ClipImageEncoder
import ai.dusty.finderplus.vision.ClipTextEncoder
import ai.dusty.finderplus.vision.DefaultImageAnalyzer
import ai.dusty.finderplus.vision.ImageAnalyzer
import ai.dusty.finderplus.vision.OcrDictionary
import ai.dusty.finderplus.vision.OnnxImageLabeler
import ai.dusty.finderplus.vision.PaddleOcrReader
import ai.dusty.finderplus.vision.OnnxClipImageEncoder
import ai.dusty.finderplus.vision.OnnxClipTextEncoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * On-device model wiring. Model files are resolved from [ModelManager]; when a model isn't installed
 * yet, the ONNX-backed encoders return zero vectors so the pipeline stays wired and search degrades
 * gracefully to the FTS leg. See docs/design/03-AI-PIPELINE.md.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    private const val CLIP_DIM = 512
    private const val TEXT_DIM = 384

    @Provides @Singleton
    fun modelPrefs(@ApplicationContext context: Context): ModelPrefs = object : ModelPrefs {
        private val sp = context.getSharedPreferences("finder-models", Context.MODE_PRIVATE)
        override fun getSelectedAsrId(): String? = sp.getString("asr_id", null)
        override fun setSelectedAsrId(id: String) { sp.edit().putString("asr_id", id).apply() }
    }

    @Provides @Singleton
    fun modelManager(@ApplicationContext context: Context, prefs: ModelPrefs): ModelManager =
        FileModelManager(File(context.filesDir, "models"), prefs)

    @Provides @Singleton
    fun objectDetector(models: ModelManager): ObjectDetector =
        // YOLOX-tiny (Apache-2.0): 80 concrete COCO classes, replacing ML Kit's `multiple objects`
        // buckets. Resolved at construction; the OBJECTS pass parks as SKIPPED until installed.
        ai.dusty.finderplus.vision.OnnxYoloDetector(
            modelPath = models.pathOf(ModelCatalog.YOLO_DETECT.id) ?: "",
        )

    @Provides @Singleton
    fun vlmCaptioner(models: ModelManager, perfPrefs: ai.dusty.finderplus.index.PerfPrefs): ai.dusty.finderplus.speech.VlmCaptioner =
        // Paths resolved per call so a download taking effect never needs a restart — same policy as ASR.
        ai.dusty.finderplus.speech.VlmCaptioner(
            modelPath = { models.pathOf(ModelCatalog.VLM_SMOL.id) },
            projectorPath = { models.pathOf(ModelCatalog.VLM_SMOL_PROJ.id) },
            useGpu = { perfPrefs.useGpu },
        )

    /**
     * The judge's own VLM context — separate from the caption model, Qwen chat template, and GPU
     * unconditionally: a 4B prefill on little cores is minutes per image, so the widget's CPU/GPU
     * toggle does not apply here. ggml falls back to CPU by itself only when no Vulkan device exists,
     * which is the single case where CPU is acceptable because it is the only option.
     */
    @Provides @Singleton
    fun localJudge(models: ModelManager): ai.dusty.finderplus.index.LocalJudge =
        ai.dusty.finderplus.index.LocalJudge(
            ai.dusty.finderplus.speech.VlmCaptioner(
                modelPath = { models.pathOf(ModelCatalog.VLM_JUDGE.id) },
                projectorPath = { models.pathOf(ModelCatalog.VLM_JUDGE_PROJ.id) },
                useGpu = { true },
                family = ai.dusty.finderplus.speech.VlmCaptioner.Family.QWEN,
            )
        )

    @Provides @Singleton
    fun cloudJudge(assist: ai.dusty.finderplus.index.AssistPrefs): ai.dusty.finderplus.index.CloudJudge =
        ai.dusty.finderplus.index.CloudJudge(
            provider = { assist.provider },
            apiKey = { assist.apiKey.takeIf { it.isNotBlank() } },
            model = { assist.cloudModel },
            ollamaUrl = { assist.ollamaUrl },
        )

    @Provides @Singleton
    fun faceAnalyzer(models: ModelManager): FaceAnalyzer =
        // YuNet (MIT): detection only, resolved at construction; the FACES pass parks until installed.
        OnnxFaceDetector(models.pathOf(ModelCatalog.FACE_DETECT_YUNET.id) ?: "")

    @Provides @Singleton
    fun clipImageEncoder(models: ModelManager): ClipImageEncoder =
        OnnxClipImageEncoder(models.pathOf(ModelCatalog.CLIP_IMAGE.id) ?: "", ModelCatalog.CLIP_IMAGE.id, CLIP_DIM)

    @Provides @Singleton
    fun clipTextEncoder(models: ModelManager): ClipTextEncoder =
        OnnxClipTextEncoder(
            modelPath = models.pathOf(ModelCatalog.CLIP_TEXT.id) ?: "",
            vocabPath = models.pathOf(ModelCatalog.CLIP_VOCAB.id) ?: "",
            mergesPath = models.pathOf(ModelCatalog.CLIP_MERGES.id) ?: "",
            modelId = ModelCatalog.CLIP_TEXT.id,
            dim = CLIP_DIM,
        )

    @Provides @Singleton
    fun textEmbedder(models: ModelManager): TextEmbedder =
        OnnxTextEmbedder(models.pathOf(ModelCatalog.TEXT_EMBEDDER.id) ?: "", ModelCatalog.TEXT_EMBEDDER.id, TEXT_DIM)

    @Provides @Singleton
    fun imageAnalyzer(
        @ApplicationContext context: Context,
        models: ModelManager,
        clip: ClipImageEncoder,
    ): ImageAnalyzer =
        DefaultImageAnalyzer(
            // MobileNetV2 labeler (Apache-2.0), path resolved at construction.
            labeler = OnnxImageLabeler(models.pathOf(ModelCatalog.LABELER_MOBILENET.id) ?: ""),
            // PP-OCR (Apache-2.0): a script-agnostic detector + recognizer + its dictionary. Paths and
            // dictionary are resolved per call, so installing or swapping a script pack takes effect
            // without a restart. The recognizer's script is data (see [OcrDictionary]), not code.
            ocrReader = PaddleOcrReader(
                detPath = { models.pathOf(ModelCatalog.OCR_DET_V5.id) },
                recPath = { models.pathOf(ModelCatalog.OCR_REC_LATIN_V5.id) },
                dictionary = { OcrDictionary.symbols(context) },
            ),
            clip = clip,
        )

    /**
     * Speech, routed to whichever engine the selected model needs — Qwen3-ASR via llama.cpp's mtmd path,
     * or Whisper via whisper.cpp. Config and engine are both resolved lazily on every call, so a model
     * finishing its download, or the user switching engines, takes effect with no restart.
     */
    @Provides @Singleton
    fun speechRecognizer(
        pcm: PcmDecoder,
        models: ModelManager,
        perfPrefs: ai.dusty.finderplus.index.PerfPrefs,
    ): SpeechRecognizer =
        ai.dusty.finderplus.speech.DelegatingSpeechRecognizer(
            pcm = pcm,
            // The widget's CPU/GPU switch overrides the caller's preference, so a tap takes effect on
            // the next file with no restart.
            configProvider = { _ ->
                models.asrConfig(if (perfPrefs.useGpu) Accelerator.GPU_VULKAN else Accelerator.CPU)
            },
            engineProvider = { models.selectedEngine() },
            // Resolved per call, not captured: the VAD is a 2.3 MB download that may arrive after the
            // recognizer exists, and it must start gating on the next window rather than the next launch.
            vadPath = { models.pathOf(ModelCatalog.VAD_SILERO.id) },
        )
}

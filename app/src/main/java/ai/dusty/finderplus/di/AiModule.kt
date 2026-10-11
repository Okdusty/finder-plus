package ai.dusty.finderplus.di

import ai.dusty.finderplus.settings.DescribeSize
import ai.dusty.finderplus.settings.MediaSettings
import ai.dusty.finderplus.settings.MemorySettings
import ai.dusty.finderplus.settings.ProcessingSettings
import ai.dusty.finderplus.settings.Settings
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
import ai.dusty.finderplus.text.FlorenceDescriber
import ai.dusty.finderplus.vision.ImageDescriber
import ai.dusty.finderplus.text.EmbeddingGemmaEmbedder
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

    @Provides @Singleton
    fun modelPrefs(@ApplicationContext context: Context): ModelPrefs = object : ModelPrefs {
        private val sp = context.getSharedPreferences("finder-models", Context.MODE_PRIVATE)
        override fun getSelectedAsrId(): String? = sp.getString("asr_id", null)
        override fun setSelectedAsrId(id: String) { sp.edit().putString("asr_id", id).apply() }
    }

    @Provides @Singleton
    fun modelManager(@ApplicationContext context: Context, prefs: ModelPrefs): ModelManager =
        FileModelManager(File(context.filesDir, "models"), prefs)

    /**
     * Florence-2, whichever checkpoint is fully installed (large first). Resolved per use, so a finished
     * download takes effect without a restart; the describe pass parks until one is installed.
     */
    @Provides @Singleton
    fun imageDescriber(models: ModelManager): ImageDescriber {
        fun installed(): ModelCatalog.Florence? {
            val order = if (Settings[MemorySettings.DESCRIBE_SIZE] == DescribeSize.BASE) ModelCatalog.florencePreference.reversed()
                else ModelCatalog.florencePreference
            return order.firstOrNull { f -> f.all.all { models.pathOf(it.id) != null } }
        }
        return FlorenceDescriber(
            files = {
                installed()?.let { f ->
                    val p = f.all.map { models.pathOf(it.id) ?: return@let null }
                    FlorenceDescriber.Files(
                        visionEncoder = p[0], encoder = p[1], embedTokens = p[2], decoder = p[3],
                        tokenizer = p[4], config = p[5], preprocessor = p[6],
                    )
                }
            },
            id = { installed()?.visionEncoder?.id },
        )
    }

    /**
     * The judge has its own VLM context (Qwen chat template) and asks for the GPU: a 4B prefill on CPU
     * takes minutes per image, so the widget's CPU/GPU toggle does not apply; only "processor only"
     * does. ggml still falls back to CPU when no Vulkan device exists.
     */
    @Provides @Singleton
    fun localJudge(models: ModelManager): ai.dusty.finderplus.index.LocalJudge =
        ai.dusty.finderplus.index.LocalJudge(
            ai.dusty.finderplus.speech.VlmCaptioner(
                modelPath = { models.pathOf(ModelCatalog.VLM_JUDGE.id) },
                projectorPath = { models.pathOf(ModelCatalog.VLM_JUDGE_PROJ.id) },
                // GPU unless the user chose the processor only for every model.
                useGpu = { !Settings[ai.dusty.finderplus.settings.AccelerationSettings.PROCESSOR_ONLY] },
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
        // YuNet (MIT): detection only, resolved per use; the FACES pass parks until installed.
        OnnxFaceDetector({ models.pathOf(ModelCatalog.FACE_DETECT_YUNET.id) })

    @Provides @Singleton
    fun clipImageEncoder(models: ModelManager): ClipImageEncoder =
        OnnxClipImageEncoder({ models.pathOf(ModelCatalog.CLIP_IMAGE.id) }, ModelCatalog.CLIP_IMAGE.id, CLIP_DIM)

    @Provides @Singleton
    fun clipTextEncoder(models: ModelManager): ClipTextEncoder =
        OnnxClipTextEncoder(
            modelPath = { models.pathOf(ModelCatalog.CLIP_TEXT.id) },
            vocabPath = { models.pathOf(ModelCatalog.CLIP_VOCAB.id) },
            mergesPath = { models.pathOf(ModelCatalog.CLIP_MERGES.id) },
            modelId = ModelCatalog.CLIP_TEXT.id,
            dim = CLIP_DIM,
        )

    /**
     * EmbeddingGemma 2. The graph's path is only returned once its weights are installed too — ONNX
     * Runtime resolves the external `.onnx_data` beside it, and a graph without weights fails to load.
     */
    @Provides @Singleton
    fun textEmbedder(models: ModelManager): TextEmbedder =
        EmbeddingGemmaEmbedder(
            modelPath = {
                models.pathOf(ModelCatalog.TEXT_EMBEDDER_WEIGHTS.id)?.let { models.pathOf(ModelCatalog.TEXT_EMBEDDER.id) }
            },
            tokenizerPath = { models.pathOf(ModelCatalog.TEXT_EMBEDDER_TOKENIZER.id) },
            modelId = ModelCatalog.TEXT_SPACE_ID,
            dims = { Settings[ai.dusty.finderplus.settings.ModelSizeSettings.MEANING_DIMS].dims },
        )

    @Provides @Singleton
    fun imageAnalyzer(
        @ApplicationContext context: Context,
        models: ModelManager,
        clip: ClipImageEncoder,
    ): ImageAnalyzer =
        DefaultImageAnalyzer(
            // MobileNetV2 labeler (Apache-2.0), path resolved per use.
            labeler = OnnxImageLabeler({ models.pathOf(ModelCatalog.LABELER_MOBILENET.id) }),
            // PP-OCR (Apache-2.0). Paths and dictionary are resolved per call, so installing or
            // swapping a script pack needs no restart; the script is data (see [OcrDictionary]).
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
                models.asrConfig(if (perfPrefs.useGpu) Accelerator.GPU_VULKAN else Accelerator.CPU)?.let { cfg ->
                    cfg.copy(
                        threads = Settings.valueOrNull(ProcessingSettings.NATIVE_THREADS)?.toInt() ?: cfg.threads,
                        languageHint = Settings[MediaSettings.SPEECH_LANGUAGE].trim().ifEmpty { null } ?: cfg.languageHint,
                    )
                }
            },
            engineProvider = { models.selectedEngine() },
            // Resolved per call, not captured: the VAD is a 2.3 MB download that may arrive after the
            // recognizer exists, and it must start gating on the next window rather than the next launch.
            vadPath = { models.pathOf(ModelCatalog.VAD_SILERO.id) },
        )
}

package ai.dusty.finderplus.model

/** Which pipeline role a downloadable model fills. Maps to [RequiredModel] for residency. */
enum class ModelRole { ASR, ASR_PROJECTOR, ASR_WHISPER, CLIP_IMAGE, CLIP_TEXT, TEXT_EMBEDDER, LABELER, FACE_DETECTOR, OCR_DATA, TOKENIZER, VAD, VLM, VLM_PROJECTOR, DETECTOR }

/** Compute backend an on-device model should try to use, in order of preference. */
enum class Accelerator {
    /** Vulkan compute on the phone GPU (Xclipse 940 on Exynos 2400). Falls back automatically. */
    GPU_VULKAN,

    /** CPU with NEON/i8mm kernels. Always available; the safe default. */
    CPU,
}

/** A downloadable, verifiable on-device model. Optional so the base install stays small. */
data class ModelSpec(
    val id: String,
    val role: ModelRole,
    val label: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val languages: Int,
    val pipelineVersion: Int,
    /** Companion spec that must also be installed for this model to work (ASR audio projector). */
    val requiresId: String? = null,
    /** Free-form note surfaced in Settings so the storage/quality trade-off is explicit. */
    val note: String = "",
)

data class DownloadProgress(val received: Long, val total: Long, val verifying: Boolean) {
    val fraction: Float get() = if (total <= 0) 0f else received.toFloat() / total
}

/**
 * Downloadable on-device models. Sizes are the real published artifact sizes.
 *
 * Speech uses **Qwen3-ASR** (Apache-2.0) run through llama.cpp's `mtmd` audio path, which officially
 * supports both the 0.6B and 1.7B variants. It is chosen over Whisper because its 30 supported
 * languages span less-resourced ones where Whisper's tiny/base tiers degrade badly. Each ASR model
 * needs its companion **mmproj** audio projector, hence [ModelSpec.requiresId].
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co"

    // ---- Speech: Qwen3-ASR 0.6B (bulk indexing default) ----
    val ASR_QWEN3_06B = ModelSpec(
        id = "qwen3-asr-0.6b-q8",
        role = ModelRole.ASR,
        label = "Speech · Qwen3-ASR 0.6B",
        sizeBytes = 805_000_000,
        sha256 = "",
        url = "$HF/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/Qwen3-ASR-0.6B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        requiresId = "qwen3-asr-0.6b-proj",
        note = "Best speed/quality balance for indexing a whole gallery. Broad multilingual coverage.",
    )
    val ASR_QWEN3_06B_PROJ = ModelSpec(
        id = "qwen3-asr-0.6b-proj",
        role = ModelRole.ASR_PROJECTOR,
        label = "Speech · 0.6B audio encoder",
        sizeBytes = 214_000_000,
        sha256 = "",
        url = "$HF/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/main/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        note = "Required audio projector for the 0.6B model.",
    )

    // ---- Speech: Qwen3-ASR 1.7B (best quality, heavy) ----
    val ASR_QWEN3_17B = ModelSpec(
        id = "qwen3-asr-1.7b-q8",
        role = ModelRole.ASR,
        label = "Speech · Qwen3-ASR 1.7B",
        sizeBytes = 2_165_000_000,
        sha256 = "",
        url = "$HF/ggml-org/Qwen3-ASR-1.7B-GGUF/resolve/main/Qwen3-ASR-1.7B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        requiresId = "qwen3-asr-1.7b-proj",
        note = "Highest accuracy. ~2.5 GB with its encoder and well below real-time — transcription is gated to charging.",
    )
    val ASR_QWEN3_17B_PROJ = ModelSpec(
        id = "qwen3-asr-1.7b-proj",
        role = ModelRole.ASR_PROJECTOR,
        label = "Speech · 1.7B audio encoder",
        sizeBytes = 356_000_000,
        sha256 = "",
        url = "$HF/ggml-org/Qwen3-ASR-1.7B-GGUF/resolve/main/mmproj-Qwen3-ASR-1.7B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        note = "Required audio projector for the 1.7B model.",
    )

    // ---- Speech: Whisper via whisper.cpp (fast engine) ----
    //
    // Added because Qwen3-ASR measured ~1.6 s of compute per 1 s of audio on the Exynos 2400
    // (prefill-dominated, exactly linear in length), putting a 6.4-hour backlog at ~10 hours. Whisper's
    // encoder is far more optimized and it keeps wide language coverage, which is why it wins over the
    // faster alternatives: NVIDIA Parakeet's 25 languages omit key ones, and Nemotron-ASR ships only NeMo
    // checkpoints with no on-device inference path. MIT licensed, unlike the Apple vision weights.
    val ASR_WHISPER_SMALL = ModelSpec(
        id = "whisper-small-q5",
        role = ModelRole.ASR_WHISPER,
        label = "Speech · Whisper small (fast)",
        sizeBytes = 190_100_000,
        sha256 = "",
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        languages = 99,
        pipelineVersion = 1,
        note = "Much faster than Qwen3-ASR and a quarter the size. Multilingual, but weaker than the large tier.",
    )
    val ASR_WHISPER_TURBO = ModelSpec(
        id = "whisper-turbo-q5",
        role = ModelRole.ASR_WHISPER,
        label = "Speech · Whisper large-v3-turbo",
        sizeBytes = 574_000_000,
        sha256 = "",
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        languages = 99,
        pipelineVersion = 1,
        note = "Best multilingual accuracy of the Whisper tiers. Only 4 decoder layers, so decoding stays quick; the encoder is the cost.",
    )

    // ---- Vision ----
    /**
     * CLIP **ViT-B/16** image tower.
     *
     * Upgraded from ViT-B/32. The two have essentially the same parameter count — and so nearly the
     * same file size — but B/16 splits the 224 px frame into 14x14 = 196 patches instead of 7x7 = 49.
     * That is 4x the spatial resolution for ~2-3x the compute and ~no extra storage, which is what
     * makes small subjects (a face in a group shot, text in a meme) survive the encoder instead of
     * being averaged into one of 49 coarse cells.
     */
    val CLIP_IMAGE = ModelSpec(
        id = "clip-img", role = ModelRole.CLIP_IMAGE, label = "Visual search (image)",
        sizeBytes = 345_060_000, sha256 = "",
        url = "$HF/Xenova/clip-vit-base-patch16/resolve/main/onnx/vision_model.onnx",
        languages = 0, pipelineVersion = 2,
        note = "ViT-B/16: 196 patches vs 49. Semantic image search, and the space all learned labels live in.",
    )
    /**
     * The text half of the *same* CLIP checkpoint as [CLIP_IMAGE] — pairing towers from different
     * checkpoints silently yields garbage similarities, so these two ids must always move together.
     *
     * It earns its size by doing more than search: it turns any label string into a vector in the
     * image space, which is what gives a freshly-named label a working classifier before the user has
     * shown a single example.
     */
    val CLIP_TEXT = ModelSpec(
        id = "clip-txt", role = ModelRole.CLIP_TEXT, label = "Visual search (query)",
        sizeBytes = 254_060_000, sha256 = "",
        url = "$HF/Xenova/clip-vit-base-patch16/resolve/main/onnx/text_model.onnx",
        languages = 100, pipelineVersion = 2,
        requiresId = "clip-vocab",
        note = "Encodes search text and label names into the same space as images. Enables zero-shot labels.",
    )
    val CLIP_VOCAB = ModelSpec(
        id = "clip-vocab", role = ModelRole.TOKENIZER, label = "Query tokenizer (vocabulary)",
        sizeBytes = 862_000, sha256 = "",
        url = "$HF/Xenova/clip-vit-base-patch16/resolve/main/vocab.json",
        languages = 100, pipelineVersion = 2,
        requiresId = "clip-merges",
        note = "CLIP's 49,408-token BPE vocabulary.",
    )
    val CLIP_MERGES = ModelSpec(
        id = "clip-merges", role = ModelRole.TOKENIZER, label = "Query tokenizer (merges)",
        sizeBytes = 525_000, sha256 = "",
        url = "$HF/Xenova/clip-vit-base-patch16/resolve/main/merges.txt",
        languages = 100, pipelineVersion = 2,
        note = "BPE merge ranks. Useless without the vocabulary, and vice versa.",
    )

    /**
     * Identifies the encoder that produced the stored vectors.
     *
     * Every embedding, every text prior and every learned prototype is only meaningful relative to one
     * checkpoint. Swapping towers therefore invalidates all of them at once — and because both towers
     * emit 512-d unit vectors, nothing would *fail*; search would just quietly return unrelated
     * results. This constant is what lets the app notice the change and rebuild instead.
     */
    const val CLIP_SPACE_ID = "clip-vit-b16"

    /**
     * MobileNetV2 image labeler — the FOSS replacement for the bundled ML Kit labeler.
     *
     * ImageNet-1k, 1000 classes. Preprocessing and the Apache-2.0 licence were validated against the
     * ONNX model zoo reference, and inference was checked on a real gallery photo ("menu" top-1 at
     * 0.60). ImageNet has no "person" class — people come from the face detector and YOLOX instead.
     * **Licence:** Apache-2.0.
     */
    val LABELER_MOBILENET = ModelSpec(
        id = "labeler-mobilenetv2", role = ModelRole.LABELER, label = "Image labeling",
        sizeBytes = 13_964_571, sha256 = "c0c3f76d93fa3fd6580652a45618618a220fced18babf65774ed169de0432ad5",
        url = "https://github.com/onnx/models/raw/main/validated/vision/classification/mobilenet/model/mobilenetv2-12.onnx",
        languages = 0, pipelineVersion = 3,
        note = "MobileNetV2, 1000 ImageNet classes (objects, animals, food, scenes). Apache-2.0.",
    )

    /**
     * YuNet face **detector** — the FOSS replacement for the bundled ML Kit face detector.
     *
     * Detection only: how many faces and how prominent, which feeds the people/selfie/portrait tags.
     * There is no identity/grouping model — the only practical face-recognition weights are
     * non-commercial, a licensing blocker for a FOSS build. Decode validated against OpenCV's own
     * FaceDetectorYN on real gallery faces (box IoU 0.79-0.83). **Licence:** MIT (OpenCV Zoo).
     */
    val FACE_DETECT_YUNET = ModelSpec(
        id = "face-detect-yunet", role = ModelRole.FACE_DETECTOR, label = "Face detection",
        sizeBytes = 232_589, sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
        url = "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        languages = 0, pipelineVersion = 3,
        note = "YuNet, 0.2 MB. Detects faces for the people/selfie/portrait tags. MIT.",
    )

    /**
     * PP-OCR mobile OCR (detector + recognizer) — the FOSS replacement for the ML Kit text recognizer,
     * run through ONNX Runtime like the rest of the vision stack.
     *
     * Two models, both Apache-2.0 ONNX exports converted from the official PaddleX archives per
     * PaddleOCR's ONNX guide. The detector is script-agnostic. The recognizer [requiresId] the
     * detector, so selecting recognition pulls both. The recognizer's script is not fixed in code: its
     * label map is loaded as data (see the vision module's `OcrDictionary`), so swapping in another
     * PP-OCR recognizer + dictionary — Latin, CJK, Cyrillic, Arabic, Devanagari — is a config change,
     * not a code change. The default pack recognizes the Latin script family (broad multilingual
     * coverage with full diacritics), plus punctuation, currency, Roman numerals and symbols.
     *
     * Chosen over Tesseract for accuracy on the noisy, low-contrast meme and screenshot text this
     * gallery is full of, at a comparable download size and with no extra native dependency (it reuses
     * the ONNX Runtime already shipped for CLIP). **Licence:** Apache-2.0.
     */
    val OCR_DET_V5 = ModelSpec(
        id = "ocr-det-v5", role = ModelRole.OCR_DATA, label = "OCR (text detection)",
        sizeBytes = 4_748_769, sha256 = "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924",
        url = "$HF/webnn/PP-OCRv5-ONNX/resolve/main/ch_PP-OCRv5_det.onnx",
        languages = 0, pipelineVersion = 4,
        note = "PP-OCRv5 mobile text detector. Locates text regions for the recognizer, any script. Apache-2.0.",
    )
    val OCR_REC_LATIN_V5 = ModelSpec(
        id = "ocr-rec-latin-v5", role = ModelRole.OCR_DATA, label = "OCR (text recognition)",
        sizeBytes = 8_068_114, sha256 = "bc243bda530317419d3a24ee66af5406e672fceaca19627247f22a628920f778",
        url = "$HF/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer/resolve/main/inference.onnx",
        languages = 80, pipelineVersion = 4,
        requiresId = "ocr-det-v5",
        note = "PP-OCRv5 mobile recognizer, Latin script family (multilingual). Pulls the detector. Apache-2.0.",
    )

    /**
     * Silero VAD — decides whether a 30-second window contains speech before ASR is spent on it.
     *
     * 2.3 MB that guards a 15-hour job. Transcription is ~75% of this gallery's index time and its cost
     * is the audio encoder, which runs whether or not anyone is talking. The energy detector already in
     * the pipeline cannot make this call: on 18 files with known transcripts, the ones that produced
     * nothing measured -17.2 dB mean volume and the ones that produced full transcripts measured
     * -17.1 dB. They are not quiet, they are music and laughter.
     *
     * **Licence:** MIT.
     */
    val VAD_SILERO = ModelSpec(
        id = "vad-silero", role = ModelRole.VAD, label = "Speech detection",
        sizeBytes = 2_327_000, sha256 = "",
        url = "https://github.com/snakers4/silero-vad/raw/master/src/silero_vad/data/silero_vad.onnx",
        languages = 0, pipelineVersion = 1,
        note = "2.3 MB. Skips transcribing windows with no speech, which is 20% of them, and stops the recognizer inventing sentences over music.",
    )

    /**
     * YOLOX-tiny object detector — 80 concrete COCO classes with boxes. 20 MB, ~160 ms/image.
     *
     * Replaces ML Kit's detector, whose most frequent gallery output was `multiple objects`.
     * **Licence:** Apache-2.0 — shippable, unlike the face weights.
     */
    val YOLO_DETECT = ModelSpec(
        id = "yolo-tiny", role = ModelRole.DETECTOR, label = "Object detection",
        sizeBytes = 20_400_000, sha256 = "",
        url = "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx",
        languages = 0, pipelineVersion = 1,
        note = "YOLOX-tiny, 80 everyday object classes (person, dog, car, cup...). Apache-2.0.",
    )

    /**
     * SmolVLM-256M — the smallest usable vision-language model, for one-sentence captions.
     *
     * Chosen for exactly one job: say what a photo *is* in ~15 tokens so it becomes text-searchable.
     * The earlier per-file VLM attempt (Gemma) was measured at ~580x CLIP's cost and rejected; at 256M
     * with a token cap the cost is bounded and governed. **Licence:** Apache-2.0.
     */
    val VLM_SMOL = ModelSpec(
        id = "vlm-smol", role = ModelRole.VLM, label = "Scene descriptions",
        sizeBytes = 175_000_000, sha256 = "",
        url = "$HF/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/SmolVLM-256M-Instruct-Q8_0.gguf",
        languages = 1, pipelineVersion = 1,
        note = "SmolVLM-256M. One-sentence caption per photo / video thumbnail. Apache-2.0.",
        requiresId = "vlm-smol-proj",
    )

    val VLM_SMOL_PROJ = ModelSpec(
        id = "vlm-smol-proj", role = ModelRole.VLM_PROJECTOR, label = "Scene descriptions - vision tower",
        sizeBytes = 104_000_000, sha256 = "",
        url = "$HF/ggml-org/SmolVLM-256M-Instruct-GGUF/resolve/main/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",
        languages = 1, pipelineVersion = 1,
    )

    /**
     * Qwen3-VL-4B — the *judge*: a much stronger VLM that answers the review queue's yes/no questions
     * so the user does not have to.
     *
     * Sized deliberately: SmolVLM-256M captions cheaply but cannot be trusted to *verify* labels;
     * 4B can. It runs rarely (only over the uncertain band) and always on the GPU, so its cost is
     * bounded by the queue rather than the gallery. **Licence:** Apache-2.0.
     *
     * Qwen3.5-4B rather than Qwen3-VL-4B: natively multimodal (its mmproj declares the same
     * `qwen3vl_merger` projector our llama.cpp build already supports), newer training, same size
     * class — a drop-in through the identical loader and chat template.
     */
    val VLM_JUDGE = ModelSpec(
        id = "vlm-judge", role = ModelRole.VLM, label = "AI review assistant",
        sizeBytes = 2_500_000_000, sha256 = "",
        url = "$HF/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
        languages = 30, pipelineVersion = 2,
        note = "Qwen3.5 4B. Answers label questions and rewrites weak captions so you review less. ~3.3 GB with its vision tower. Apache-2.0.",
        requiresId = "vlm-judge-proj",
    )

    val VLM_JUDGE_PROJ = ModelSpec(
        id = "vlm-judge-proj", role = ModelRole.VLM_PROJECTOR, label = "AI review assistant - vision tower",
        sizeBytes = 836_000_000, sha256 = "",
        url = "$HF/unsloth/Qwen3.5-4B-GGUF/resolve/main/mmproj-F16.gguf",
        languages = 30, pipelineVersion = 2,
    )

    val TEXT_EMBEDDER = ModelSpec(
        id = "text-emb", role = ModelRole.TEXT_EMBEDDER, label = "Text search",
        sizeBytes = 110_000_000, sha256 = "", url = "", languages = 100, pipelineVersion = 1,
    )

    val all = listOf(
        ASR_QWEN3_06B, ASR_QWEN3_06B_PROJ, ASR_QWEN3_17B, ASR_QWEN3_17B_PROJ,
        ASR_WHISPER_SMALL, ASR_WHISPER_TURBO,
        CLIP_IMAGE, CLIP_TEXT, CLIP_VOCAB, CLIP_MERGES, TEXT_EMBEDDER, VAD_SILERO,
        LABELER_MOBILENET, FACE_DETECT_YUNET, OCR_DET_V5, OCR_REC_LATIN_V5,
        YOLO_DETECT, VLM_SMOL, VLM_SMOL_PROJ, VLM_JUDGE, VLM_JUDGE_PROJ,
    )

    /** Default speech model: 0.6B keeps a full-gallery pass feasible. */
    val defaultAsr = ASR_QWEN3_06B
}

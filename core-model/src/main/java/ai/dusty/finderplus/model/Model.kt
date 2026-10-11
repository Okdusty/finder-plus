package ai.dusty.finderplus.model

/** Which pipeline role a downloadable model fills. Maps to [RequiredModel] for residency. */
enum class ModelRole { ASR, ASR_PROJECTOR, ASR_WHISPER, CLIP_IMAGE, CLIP_TEXT, TEXT_EMBEDDER, LABELER, FACE_DETECTOR, OCR_DATA, TOKENIZER, VAD, VLM, VLM_PROJECTOR, DETECTOR, DESCRIBER }

/** Compute backend an on-device model should try to use, in order of preference. */
enum class Accelerator {
    /** Vulkan compute on the GPU. Falls back automatically. */
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
    /**
     * Path under the models directory, when the file's name matters. ONNX models with external
     * weights reference their `.onnx_data` sibling by its exact file name, so those specs keep the
     * upstream names inside a folder of their own instead of the default `<id>.<ext>`.
     */
    val fileName: String? = null,
    /** Which part of the app this model powers — how Settings groups it. */
    val category: ModelCategory = ModelCategory.SEARCH,
    /** How strongly it is recommended; drives the one-tap setup bundles. */
    val tier: ModelTier = ModelTier.OPTIONAL,
)

/** What a model is *for*, in the words Settings uses. */
enum class ModelCategory(val title: String, val blurb: String) {
    SEARCH("Smart search", "Find things by meaning, in any language."),
    VISION("Photos & objects", "Read text, spot objects and faces, recognize scenes."),
    SPEECH("Speech", "Make what is said in videos and audio searchable."),
    DESCRIBE("Descriptions & review", "Write captions and answer label questions for you."),
}

/**
 * How a model is offered. [ESSENTIAL] is small and transforms search; [RECOMMENDED] is larger but
 * worth it on most phones; [OPTIONAL] is heavy or an alternative to something already recommended.
 */
enum class ModelTier { ESSENTIAL, RECOMMENDED, OPTIONAL }

data class DownloadProgress(val received: Long, val total: Long, val verifying: Boolean) {
    val fraction: Float get() = if (total <= 0) 0f else received.toFloat() / total
}

/**
 * Downloadable on-device models. Sizes are the published artifact sizes.
 *
 * Speech has two engines. Qwen3-ASR (Apache-2.0) runs through llama.cpp's `mtmd` audio path and each
 * model needs its companion mmproj audio projector, hence [ModelSpec.requiresId]. Whisper runs through
 * whisper.cpp as a single file.
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co"

    /** onnx-community/embeddinggemma-2-ONNX commit the checksums below were taken from. */
    private const val EG2_REV = "daa72c51243991dfcaf9f9137d2c573d8f7790c0"

    // ---- Speech: Qwen3-ASR 0.6B (bulk indexing default) ----
    val ASR_QWEN3_06B = ModelSpec(
        id = "qwen3-asr-0.6b-q8",
        role = ModelRole.ASR,
        label = "Speech · Qwen3-ASR 0.6B",
        sizeBytes = 804_749_248,
        sha256 = "bca259818b50ca7c4c05e9bdb35a5dc04fa039653a6d6f3f0f331f96f6aa1971",
        url = "https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/928ab958557df9aa2ef1c93e0e83c7ad0933fae2/Qwen3-ASR-0.6B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        requiresId = "qwen3-asr-0.6b-proj",
        note = "Best speed/quality balance for indexing a whole gallery. Broad multilingual coverage.",
        category = ModelCategory.SPEECH, tier = ModelTier.OPTIONAL,
    )
    val ASR_QWEN3_06B_PROJ = ModelSpec(
        id = "qwen3-asr-0.6b-proj",
        role = ModelRole.ASR_PROJECTOR,
        label = "Speech · 0.6B audio encoder",
        sizeBytes = 214_392_480,
        sha256 = "41a342b5e4c514e968cb756de6cd1b7be39eff43c44c57a2ef5fc6522e36603d",
        url = "https://huggingface.co/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/928ab958557df9aa2ef1c93e0e83c7ad0933fae2/mmproj-Qwen3-ASR-0.6B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        note = "Required audio projector for the 0.6B model.",
        category = ModelCategory.SPEECH,
        requiresId = "vad-silero",
    )

    // ---- Speech: Qwen3-ASR 1.7B (best quality, heavy) ----
    val ASR_QWEN3_17B = ModelSpec(
        id = "qwen3-asr-1.7b-q8",
        role = ModelRole.ASR,
        label = "Speech · Qwen3-ASR 1.7B",
        sizeBytes = 2_165_034_944,
        sha256 = "58e22d0532d4eacaf034cfac17a6fed159f37c41390c710186783be439d1fc57",
        url = "https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/resolve/36a678687ba7d07a74ca70ccb0e36902e005fb80/Qwen3-ASR-1.7B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        requiresId = "qwen3-asr-1.7b-proj",
        note = "Highest accuracy. ~2.5 GB with its encoder and well below real-time — transcription is gated to charging.",
        category = ModelCategory.SPEECH, tier = ModelTier.OPTIONAL,
    )
    val ASR_QWEN3_17B_PROJ = ModelSpec(
        id = "qwen3-asr-1.7b-proj",
        role = ModelRole.ASR_PROJECTOR,
        label = "Speech · 1.7B audio encoder",
        sizeBytes = 355_709_344,
        sha256 = "46c1d533af3f354ceb37ce855dbceff7da7fa7cf1e6a523df3b13440bd164c0d",
        url = "https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/resolve/36a678687ba7d07a74ca70ccb0e36902e005fb80/mmproj-Qwen3-ASR-1.7B-Q8_0.gguf",
        languages = 30,
        pipelineVersion = 2,
        note = "Required audio projector for the 1.7B model.",
        category = ModelCategory.SPEECH,
        requiresId = "vad-silero",
    )

    // ---- Speech: Whisper via whisper.cpp (fast engine) ----
    //
    // Qwen3-ASR runs slower than real time on phone hardware (prefill-dominated, linear in length).
    // Whisper's encoder is far more optimized and keeps wide language coverage, which the faster
    // alternatives do not: NVIDIA Parakeet's 25 languages omit key ones, and Nemotron-ASR ships only
    // NeMo checkpoints with no on-device inference path. MIT licensed.
    val ASR_WHISPER_SMALL = ModelSpec(
        id = "whisper-small-q5",
        role = ModelRole.ASR_WHISPER,
        label = "Speech · Whisper small (fast)",
        sizeBytes = 190_085_487,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin",
        languages = 99,
        pipelineVersion = 1,
        note = "Much faster than Qwen3-ASR and a quarter the size. Multilingual, but weaker than the large tier.",
        category = ModelCategory.SPEECH, tier = ModelTier.RECOMMENDED,
        requiresId = "vad-silero",
    )
    val ASR_WHISPER_TURBO = ModelSpec(
        id = "whisper-turbo-q5",
        role = ModelRole.ASR_WHISPER,
        label = "Speech · Whisper large-v3-turbo",
        sizeBytes = 574_041_195,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin",
        languages = 99,
        pipelineVersion = 1,
        note = "Best multilingual accuracy of the Whisper tiers. Only 4 decoder layers, so decoding stays quick; the encoder is the cost.",
        category = ModelCategory.SPEECH, tier = ModelTier.OPTIONAL,
        requiresId = "vad-silero",
    )

    // ---- Vision ----
    /**
     * CLIP ViT-B/16 image tower. Compared with ViT-B/32 it has about the same parameter count and file
     * size, but splits the 224 px frame into 14x14 = 196 patches instead of 7x7 = 49: 4x the spatial
     * resolution for ~2-3x the compute, so small subjects (a face in a group shot, small text) are not
     * averaged away.
     */
    val CLIP_IMAGE = ModelSpec(
        id = "clip-img", role = ModelRole.CLIP_IMAGE, label = "Visual search (CLIP)",
        sizeBytes = 345_060_583, sha256 = "b5170b47c0ecc667c9af5b42ed460c341377706d1466d177798c65fe49ea1e6b",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch16/resolve/342fdf2f67aded64d138ff074745fb4a5d2bba5f/onnx/vision_model.onnx",
        languages = 0, pipelineVersion = 2,
        note = "ViT-B/16: 196 patches vs 49. Semantic image search, and the space all learned labels live in.",
        category = ModelCategory.SEARCH, tier = ModelTier.RECOMMENDED,
        requiresId = "clip-txt",
    )
    /**
     * Text tower of the same CLIP checkpoint as [CLIP_IMAGE]. Towers from different checkpoints give
     * meaningless similarities, so these two must always change together.
     *
     * Besides search, it turns any label name into a vector in the image space, which gives a new
     * label a working classifier before the user has shown a single example.
     */
    val CLIP_TEXT = ModelSpec(
        id = "clip-txt", role = ModelRole.CLIP_TEXT, label = "Visual search · query encoder",
        sizeBytes = 254_058_553, sha256 = "19f40c7988af119fc50dabfe8015da8595c0372000e2c1f1a6289e35930577aa",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch16/resolve/342fdf2f67aded64d138ff074745fb4a5d2bba5f/onnx/text_model.onnx",
        languages = 100, pipelineVersion = 2,
        requiresId = "clip-vocab",
        note = "Encodes search text and label names into the same space as images. Enables zero-shot labels.",
        category = ModelCategory.SEARCH,
    )
    val CLIP_VOCAB = ModelSpec(
        id = "clip-vocab", role = ModelRole.TOKENIZER, label = "Query tokenizer (vocabulary)",
        sizeBytes = 862_328, sha256 = "5047b556ce86ccaf6aa22b3ffccfc52d391ea4accdab9c2f2407da5b742d4363",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch16/resolve/342fdf2f67aded64d138ff074745fb4a5d2bba5f/vocab.json",
        languages = 100, pipelineVersion = 2,
        requiresId = "clip-merges",
        note = "CLIP's 49,408-token BPE vocabulary.",
        category = ModelCategory.SEARCH,
    )
    val CLIP_MERGES = ModelSpec(
        id = "clip-merges", role = ModelRole.TOKENIZER, label = "Query tokenizer (merges)",
        sizeBytes = 524_619, sha256 = "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a",
        url = "https://huggingface.co/Xenova/clip-vit-base-patch16/resolve/342fdf2f67aded64d138ff074745fb4a5d2bba5f/merges.txt",
        languages = 100, pipelineVersion = 2,
        note = "BPE merge ranks. Useless without the vocabulary, and vice versa.",
        category = ModelCategory.SEARCH,
    )

    /**
     * Identifies the encoder that produced the stored vectors. Embeddings, text priors and learned
     * prototypes are only meaningful relative to one checkpoint, and another tower with the same 512-d
     * output would not fail, just return unrelated results. This id lets the app notice a swap and
     * rebuild.
     */
    const val CLIP_SPACE_ID = "clip-vit-b16"

    /**
     * MobileNetV2 image labeler, ImageNet-1k (1000 classes). ImageNet has no "person" class; people
     * come from the face detector and YOLOX. Licence: Apache-2.0.
     */
    val LABELER_MOBILENET = ModelSpec(
        id = "labeler-mobilenetv2", role = ModelRole.LABELER, label = "Image labeling",
        sizeBytes = 13_964_571, sha256 = "c0c3f76d93fa3fd6580652a45618618a220fced18babf65774ed169de0432ad5",
        url = "https://github.com/onnx/models/raw/main/validated/vision/classification/mobilenet/model/mobilenetv2-12.onnx",
        languages = 0, pipelineVersion = 3,
        note = "MobileNetV2, 1000 ImageNet classes (objects, animals, food, scenes). Apache-2.0.",
        category = ModelCategory.VISION, tier = ModelTier.ESSENTIAL,
    )

    /**
     * YuNet face detector. Detection only: face count and prominence feed the people/selfie/portrait
     * tags. There is no identity model because the practical face-recognition weights are
     * non-commercial. Licence: MIT (OpenCV Zoo).
     */
    val FACE_DETECT_YUNET = ModelSpec(
        id = "face-detect-yunet", role = ModelRole.FACE_DETECTOR, label = "Face detection",
        sizeBytes = 232_589, sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
        url = "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx",
        languages = 0, pipelineVersion = 3,
        note = "YuNet, 0.2 MB. Detects faces for the people/selfie/portrait tags. MIT.",
        category = ModelCategory.VISION, tier = ModelTier.ESSENTIAL,
    )

    /**
     * PP-OCR mobile OCR (detector + recognizer), run through ONNX Runtime like the rest of the vision
     * stack. Both are Apache-2.0 ONNX exports converted from the official PaddleX archives per
     * PaddleOCR's ONNX guide.
     *
     * The detector is script-agnostic and the recognizer requires it, so selecting recognition installs
     * both. The recognizer's label map is loaded as data (the vision module's `OcrDictionary`), so
     * another PP-OCR recognizer and dictionary is a config change, not a code change. The default pack
     * covers the Latin script family with diacritics, plus punctuation, currency and symbols.
     *
     * Chosen over Tesseract for accuracy on noisy, low-contrast text at a comparable download size, and
     * it reuses the ONNX Runtime already shipped for CLIP. Licence: Apache-2.0.
     */
    val OCR_DET_V5 = ModelSpec(
        id = "ocr-det-v5", role = ModelRole.OCR_DATA, label = "OCR (text detection)",
        sizeBytes = 4_748_769, sha256 = "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924",
        url = "https://huggingface.co/webnn/PP-OCRv5-ONNX/resolve/006b4419a415072fbb16a1d7d0d8548f789a8fae/ch_PP-OCRv5_det.onnx",
        languages = 0, pipelineVersion = 4,
        note = "PP-OCRv5 mobile text detector. Locates text regions for the recognizer, any script. Apache-2.0.",
        category = ModelCategory.VISION,
    )
    val OCR_REC_LATIN_V5 = ModelSpec(
        id = "ocr-rec-latin-v5", role = ModelRole.OCR_DATA, label = "OCR (text recognition)",
        sizeBytes = 8_068_114, sha256 = "bc243bda530317419d3a24ee66af5406e672fceaca19627247f22a628920f778",
        url = "https://huggingface.co/itextresearch/itext-latin_PP-OCRv5_mobile_rec_infer/resolve/bb953c8fd9ebf04f344eb09c9c94bb367c39033c/inference.onnx",
        languages = 80, pipelineVersion = 4,
        requiresId = "ocr-det-v5",
        note = "PP-OCRv5 mobile recognizer, Latin script family (multilingual). Pulls the detector. Apache-2.0.",
        category = ModelCategory.VISION, tier = ModelTier.ESSENTIAL,
    )

    /**
     * Silero VAD: decides whether a 30-second window contains speech before ASR is spent on it.
     * Transcription cost is the audio encoder, which runs whether or not anyone is talking, and a volume
     * threshold cannot tell speech from music or laughter at the same loudness. Licence: MIT.
     */
    val VAD_SILERO = ModelSpec(
        id = "vad-silero", role = ModelRole.VAD, label = "Speech detection",
        sizeBytes = 2_327_524, sha256 = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3",
        url = "https://github.com/snakers4/silero-vad/raw/bfdc0193023f121ea5b3cc7b176dbed570a68a59/src/silero_vad/data/silero_vad.onnx",
        languages = 0, pipelineVersion = 1,
        note = "2.3 MB. Skips transcribing windows with no speech and stops the recognizer inventing sentences over music.",
        category = ModelCategory.SPEECH,
    )

    /**
     * Florence-2 (Microsoft, MIT), the describe model: a caption and located objects for every photo and
     * video thumbnail from one read of its pixels, with an open vocabulary of object names. The `-ft`
     * checkpoints (Microsoft's own fine-tune for these tasks), int8 ONNX, pinned to one upstream commit.
     *
     * Seven files per checkpoint, chained by [ModelSpec.requiresId] so one tap installs them all; the
     * runtime reads image size, prompts and decoding rules from the shipped configs, so either
     * checkpoint runs on the same code. Neither is preselected: large is more detailed, base several
     * times faster, and the choice is the user's. When both are installed, large is used.
     */
    class Florence(val visionEncoder: ModelSpec, parts: List<ModelSpec>) {
        val encoder = parts[0]; val embedTokens = parts[1]; val decoder = parts[2]
        val tokenizer = parts[3]; val config = parts[4]; val preprocessor = parts[5]
        val all = listOf(visionEncoder) + parts
    }

    private fun florence(
        name: String, repo: String, rev: String, tier: ModelTier, note: String,
        files: List<Triple<String, Long, String>>,
    ): Florence {
        val ids = listOf("", "-encoder", "-embed", "-decoder", "-tokenizer", "-config", "-preprocessor").map { "florence-$name$it" }
        val specs = files.mapIndexed { i, (file, size, sha) ->
            ModelSpec(
                id = ids[i], role = ModelRole.DESCRIBER,
                label = if (i == 0) "Florence-2 $name · descriptions" else "Florence-2 $name · ${file.substringAfterLast('/').substringBefore('.')}",
                sizeBytes = size, sha256 = sha, url = "$HF/$repo/resolve/$rev/$file",
                languages = 1, pipelineVersion = 1,
                requiresId = ids.getOrNull(i + 1),
                fileName = "florence-2-$name/${file.substringAfterLast('/')}",
                category = ModelCategory.DESCRIBE,
                tier = if (i == 0) tier else ModelTier.OPTIONAL,
                note = if (i == 0) note else "",
            )
        }
        return Florence(specs[0], specs.drop(1))
    }

    val FLORENCE_LARGE = florence(
        "large", "onnx-community/Florence-2-large-ft", "04ace74913c28d7ec94af32cee5c111a10126b6f", ModelTier.OPTIONAL,
        note = "Describes each photo in a sentence and names the objects in it. The most detailed option, and the slowest. Microsoft, MIT.",
        files = listOf(
            Triple("onnx/vision_encoder_int8.onnx", 366_371_721L, "51d21e33e3b8cd34a9882e0611a2e4e03fb2b26daecea8accd1d4769ea11ba72"),
            Triple("onnx/encoder_model_int8.onnx", 152_985_855L, "9fe30b02cc8c2247631f8f0b9bc4d6613df1c33da0afe4087abff2f940bc2fe0"),
            Triple("onnx/embed_tokens_int8.onnx", 52_520_480L, "dab1d09ab449f1302df6e439b35ec9e93fb037134bf6bed9ffeaa93a5b35d43c"),
            Triple("onnx/decoder_model_merged_int8.onnx", 257_619_824L, "167e7807fb1e5b8d693138f84bc81e1368ea11833264131c1ea96eafc9d13222"),
            Triple("tokenizer.json", 2_297_961L, "d69dcdb2323e124ac4f800cb9863ddccea0d7bb11e16125e8df3bd60f2f8aeac"),
            Triple("config.json", 5_460L, "226291bd5b65c932b01704838489cdc810f0f4949f182abc3de67d7a6ab3632a"),
            Triple("preprocessor_config.json", 2_672L, "3adbbeec44c2b8f41a1e97b3ab2197227fb24be1fc1779c9d0d925f4e77d6559"),
        ),
    )

    val FLORENCE_BASE = florence(
        "base", "onnx-community/Florence-2-base-ft", "e88a44eaf3791a35eae0c5a47b3dbcd36e67eb6f", ModelTier.OPTIONAL,
        note = "The same descriptions and object names with less detail, at a third of the size and several times faster. Microsoft, MIT.",
        files = listOf(
            Triple("onnx/vision_encoder_int8.onnx", 93_746_478L, "d7876c1ab0f7ec11998942ca189e99a775c5a4a912b813c7745d0f6fa9343487"),
            Triple("onnx/encoder_model_int8.onnx", 43_651_493L, "f4ad7a68f1fb875d3bcf735ea14a7021b7ba7e83baf7cf10289881b4ed6d9b85"),
            Triple("onnx/embed_tokens_int8.onnx", 39_390_433L, "6b2258db1c8ee9b160576ccde3cd3814d83a2edaed0dd1c6ca9ff3c38fa62214"),
            Triple("onnx/decoder_model_merged_int8.onnx", 98_177_697L, "f22f52f980c33df0efa15932c2f3db6d9d3595ce6387eca938b8cfe23dc4c641"),
            Triple("tokenizer.json", 2_297_961L, "d69dcdb2323e124ac4f800cb9863ddccea0d7bb11e16125e8df3bd60f2f8aeac"),
            Triple("config.json", 5_432L, "d90c22ed72eb55291f183fcd9b98ebd3bd3d92bfcffb6c7f6e1606085e793525"),
            Triple("preprocessor_config.json", 2_673L, "c892857e34a7082284983a7717717d39c9bf7e574f1f41d80d4c918c97502efa"),
        ),
    )

    /** Describe checkpoints in order of preference when more than one is installed. */
    val florencePreference = listOf(FLORENCE_LARGE, FLORENCE_BASE)

    /**
     * Qwen3.5-4B, the judge: a stronger VLM that answers the review queue's yes/no questions so the
     * user does not have to. It runs only over the uncertain band and always on the GPU, so its cost
     * scales with the queue, not the library.
     *
     * Natively multimodal; its mmproj declares the `qwen3vl_merger` projector our llama.cpp build
     * supports, so it loads through the same loader and chat template as Qwen3-VL. Licence: Apache-2.0.
     */
    val VLM_JUDGE = ModelSpec(
        id = "vlm-judge", role = ModelRole.VLM, label = "AI review assistant",
        sizeBytes = 2_740_937_888, sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf",
        languages = 30, pipelineVersion = 2,
        note = "Qwen3.5 4B. Answers label questions and rewrites weak captions so you review less. ~3.4 GB with its vision tower. Apache-2.0.",
        requiresId = "vlm-judge-proj",
        category = ModelCategory.DESCRIBE, tier = ModelTier.OPTIONAL,
    )

    val VLM_JUDGE_PROJ = ModelSpec(
        id = "vlm-judge-proj", role = ModelRole.VLM_PROJECTOR, label = "AI review assistant - vision tower",
        sizeBytes = 672_423_616, sha256 = "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864",
        url = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/mmproj-F16.gguf",
        languages = 30, pipelineVersion = 2,
        category = ModelCategory.DESCRIBE,
    )

    /**
     * EmbeddingGemma 2 text tower (Google DeepMind, Apache-2.0), the semantic text leg of search. Embeds
     * each item's description, OCR, caption and transcript windows, and the query, into one
     * multilingual space, so a query in one language matches text in another. 4-bit weights from the
     * official ONNX export, within 0.989 mean cosine of fp32.
     *
     * Three files chained by [ModelSpec.requiresId] so one tap fetches them all: the graph, its external
     * weights (referenced by exact file name, hence [ModelSpec.fileName]) and the tokenizer. Pinned to
     * one upstream commit so the checksums cannot drift.
     */
    val TEXT_EMBEDDER = ModelSpec(
        id = "embeddinggemma2-text", role = ModelRole.TEXT_EMBEDDER, label = "EmbeddingGemma 2 · text search",
        sizeBytes = 490_742, sha256 = "f9eeba97acddf139b8ee2ddf04bc30dceafa88de93fadf74d7644e0d61a477a9",
        url = "$HF/onnx-community/embeddinggemma-2-ONNX/resolve/$EG2_REV/onnx/model_q4.onnx",
        languages = 100, pipelineVersion = 2,
        requiresId = "embeddinggemma2-weights",
        fileName = "embeddinggemma-2/model_q4.onnx",
        category = ModelCategory.SEARCH, tier = ModelTier.ESSENTIAL,
        note = "Matches searches by meaning rather than exact words, across 100+ languages. Google, Apache-2.0.",
    )
    val TEXT_EMBEDDER_WEIGHTS = ModelSpec(
        id = "embeddinggemma2-weights", role = ModelRole.TEXT_EMBEDDER, label = "EmbeddingGemma 2 · weights",
        sizeBytes = 174_028_800, sha256 = "c3975f2d1ab7a1878ae31a7d7a9b7804a827aff3800b60dfceafce21cac3df49",
        url = "$HF/onnx-community/embeddinggemma-2-ONNX/resolve/$EG2_REV/onnx/model_q4.onnx_data",
        languages = 100, pipelineVersion = 2,
        requiresId = "embeddinggemma2-tokenizer",
        fileName = "embeddinggemma-2/model_q4.onnx_data",
        category = ModelCategory.SEARCH,
    )
    val TEXT_EMBEDDER_TOKENIZER = ModelSpec(
        id = "embeddinggemma2-tokenizer", role = ModelRole.TOKENIZER, label = "EmbeddingGemma 2 · tokenizer",
        sizeBytes = 32_170_510, sha256 = "4d777ef5bdc1aa36227abdfb77c3e49e7b9c892d16e1b6bda41c393504828be4",
        url = "$HF/onnx-community/embeddinggemma-2-ONNX/resolve/$EG2_REV/tokenizer.json",
        languages = 100, pipelineVersion = 2,
        fileName = "embeddinggemma-2/tokenizer.json",
        category = ModelCategory.SEARCH,
    )

    /**
     * Identifies the vectors [TEXT_EMBEDDER] writes: model, quantization and Matryoshka width. Like
     * [CLIP_SPACE_ID], vectors from a different value are not comparable and are never mixed.
     */
    const val TEXT_SPACE_ID = "embeddinggemma2-q4-256"

    val all = listOf(
        ASR_QWEN3_06B, ASR_QWEN3_06B_PROJ, ASR_QWEN3_17B, ASR_QWEN3_17B_PROJ,
        ASR_WHISPER_SMALL, ASR_WHISPER_TURBO,
        CLIP_IMAGE, CLIP_TEXT, CLIP_VOCAB, CLIP_MERGES,
        TEXT_EMBEDDER, TEXT_EMBEDDER_WEIGHTS, TEXT_EMBEDDER_TOKENIZER, VAD_SILERO,
        LABELER_MOBILENET, FACE_DETECT_YUNET, OCR_DET_V5, OCR_REC_LATIN_V5,
        VLM_JUDGE, VLM_JUDGE_PROJ,
    ) + FLORENCE_LARGE.all + FLORENCE_BASE.all

    /** Default speech model: 0.6B keeps a full-gallery pass feasible. */
    val defaultAsr = ASR_QWEN3_06B

    /**
     * Speech models in the order to fall back through when the selected one is not installed —
     * fastest first, because a transcript that exists beats a better one that never finishes.
     */
    val asrPreference = listOf(ASR_WHISPER_SMALL, ASR_WHISPER_TURBO, ASR_QWEN3_06B, ASR_QWEN3_17B)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** [spec] plus everything it requires, companions first. */
    fun withCompanions(spec: ModelSpec): List<ModelSpec> {
        val out = ArrayList<ModelSpec>()
        val seen = HashSet<String>()
        fun visit(s: ModelSpec) {
            if (!seen.add(s.id)) return
            s.requiresId?.let { byId(it) }?.let(::visit)
            out += s
        }
        visit(spec)
        return out
    }

    /** Ids that only ever install as some other model's companion. */
    val companionIds: Set<String> get() = all.mapNotNull { it.requiresId }.toSet()

    /** What Settings lists: user-facing models, companions folded into their parent. */
    val userFacing: List<ModelSpec> get() = all.filter { it.id !in companionIds && it.url.isNotEmpty() }

    /** Download size of [spec] including its companions. */
    fun bundleBytes(spec: ModelSpec): Long = withCompanions(spec).sumOf { it.sizeBytes }
}

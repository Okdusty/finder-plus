package ai.dusty.finderplus.settings

import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.Pass
import ai.dusty.finderplus.settings.SettingSection.ACCELERATION
import ai.dusty.finderplus.settings.SettingSection.AUDIO
import ai.dusty.finderplus.settings.SettingSection.DESCRIBING
import ai.dusty.finderplus.settings.SettingSection.HEAT
import ai.dusty.finderplus.settings.SettingSection.MEMORY
import ai.dusty.finderplus.settings.SettingSection.OPENING
import ai.dusty.finderplus.settings.SettingSection.PHOTOS
import ai.dusty.finderplus.settings.SettingSection.PROCESSING
import ai.dusty.finderplus.settings.SettingSection.SCHEDULE
import ai.dusty.finderplus.settings.SettingSection.VIDEOS

/** The phone's own heat levels, as Android reports them. */
enum class ThermalLine(val label: String) {
    LIGHT("Light"), MODERATE("Moderate"), SEVERE("Severe"), CRITICAL("Critical"), NEVER("Never stop"),
}

enum class ModelHardware(val label: String) {
    CPU("Processor"),
    NNAPI("Accelerator (NNAPI)"),
}

enum class OpenWith(val label: String) {
    BUILT_IN("Built-in player"), DEFAULT("Default app"), ASK("Ask each time"), APP("Chosen app"),
}

enum class TapAction(val label: String) {
    COPY("Copy it"), OPEN("Open it"), PREVIEW("Preview it"),
}

enum class DescribeFrame(val label: String) {
    TYPICAL("Most typical keyframe"), THUMBNAIL("System thumbnail"),
}

enum class DescribeSize(val label: String) {
    LARGE("Large when installed"), BASE("Base when installed"),
}

object HeatSettings {
    val BATTERY_STOP = Amount(
        "heat.battery_stop_c", 40.0, HEAT, "Battery temperature limit",
        "Indexing stops when the battery reaches this temperature. −1 turns the limit off.",
        min = 30.0, max = 50.0, unit = "°C", minusOne = MinusOne.OFF, decimals = 1,
    )
    val BATTERY_START = Amount(
        "heat.battery_start_c", 37.0, HEAT, "Resume below battery temperature",
        "A new batch starts only under this temperature, so work does not stop and start around one " +
            "reading. −1 resumes as soon as it is under the limit.",
        min = 25.0, max = 50.0, unit = "°C", minusOne = MinusOne.OFF, decimals = 1, advanced = true,
    )
    val STATUS_STOP = Pick(
        "heat.status_stop", ThermalLine.SEVERE, HEAT, "Stop at the phone's heat level",
        "The heat level the phone reports at which indexing stops. It resumes one level below.",
        ThermalLine.entries, ThermalLine::label,
    )
    val HEADROOM_STOP = Amount(
        "heat.headroom_stop2", -1.0, HEAT, "Throttling forecast limit",
        "Stops when Android forecasts the phone this close to heavy throttling, where 1.0 is the point " +
            "it begins. −1 uses the phone's own value for the heat level work stops at, or 1.0 when the " +
            "phone publishes none.",
        min = 0.1, max = 1.5, minusOne = MinusOne.AUTO, decimals = 2, advanced = true,
    )
    val HEADROOM_START = Amount(
        "heat.headroom_start2", -1.0, HEAT, "Resume below forecast",
        "A new batch starts only under this forecast. −1 uses the phone's own value for the heat level " +
            "below, and where the phone publishes none, starting is left to the heat level and battery " +
            "lines: the forecast scale differs by phone, so no single number fits them all.",
        min = 0.1, max = 1.5, minusOne = MinusOne.AUTO, decimals = 2, advanced = true,
    )
    val RECHECK = Amount(
        "heat.recheck_s", 60.0, HEAT, "Check a hot phone again after",
        "How long to wait before checking whether it has cooled, on a charger. On battery the wait is " +
            "five times longer.",
        min = 15.0, max = 900.0, unit = "s", advanced = true,
    )
    val all = listOf(BATTERY_STOP, BATTERY_START, STATUS_STOP, HEADROOM_STOP, HEADROOM_START, RECHECK)
}

object ProcessingSettings {
    val FULL_SPEED = Toggle(
        "compute.full_speed", false, PROCESSING, "Full speed",
        "No battery-saving pauses between items. Heat limits still apply.",
    )
    val ONLY_CHARGING = Toggle(
        "compute.only_charging", false, PROCESSING, "Only while charging",
        "Indexing waits for a charger and stops when it is unplugged.",
    )
    val BATTERY_FLOOR = Amount(
        "compute.battery_floor_pct", 15.0, PROCESSING, "Stop below battery level",
        "On battery, indexing stops at this charge. −1 turns the limit off.",
        min = 5.0, max = 95.0, unit = "%", minusOne = MinusOne.OFF,
    )
    val THREADS = Amount(
        "compute.threads", -1.0, PROCESSING, "Processor threads",
        "Threads each image or text model uses. −1 uses two fewer than the phone has cores, at most " +
            "six. Applies when a model next loads.",
        min = 1.0, max = 16.0, minusOne = MinusOne.AUTO,
    )
    val NATIVE_THREADS = Amount(
        "compute.native_threads", -1.0, PROCESSING, "Speech and judge model threads",
        "Threads for the speech and judge models. −1 uses each model's own default. Applies when a " +
            "model next loads.",
        min = 1.0, max = 16.0, minusOne = MinusOne.AUTO, advanced = true,
    )
    val FAST_CORES = Toggle(
        "compute.fast_cores", true, PROCESSING, "Use root for the fast cores",
        "On a rooted phone, moves indexing onto the fast cores, which Android keeps background work " +
            "off. Faster, and warmer. Does nothing without root.",
        advanced = true,
    )
    val BATCH_MINUTES = Amount(
        "compute.batch_min", 4.0, PROCESSING, "Batch length",
        "Indexing works in batches with a short pause between them. Android stops background work " +
            "after ten minutes, so a batch stays under that.",
        min = 1.0, max = 9.0, unit = "min", advanced = true,
    )
    val all = listOf(FULL_SPEED, ONLY_CHARGING, BATTERY_FLOOR, THREADS, NATIVE_THREADS, FAST_CORES, BATCH_MINUTES)
}

object MemorySettings {
    val ONE_MODEL = Toggle(
        "memory.one_model", false, MEMORY, "One model in memory at a time",
        "Unloads the previous model before another loads. Uses less memory; switching costs a reload.",
    )
    val MIN_FREE_MB = Amount(
        "memory.min_free_mb", -1.0, MEMORY, "Pause below free memory",
        "Indexing waits while the phone has less free memory than this. −1 turns this off.",
        min = 128.0, max = 8192.0, unit = "MB", minusOne = MinusOne.OFF,
    )
    val RELEASE_ON_PRESSURE = Toggle(
        "memory.release_on_pressure", true, MEMORY, "Unload models when memory runs low",
        "When Android asks apps to free memory, loaded models are unloaded and load again when next needed.",
    )
    val DESCRIBE_SIZE = Pick(
        "memory.describe_size", DescribeSize.LARGE, MEMORY, "Describe model",
        "Which installed describe model to use. Base needs far less memory and is several times " +
            "faster; large names things more accurately.",
        DescribeSize.entries, DescribeSize::label,
    )
    val all = listOf(ONE_MODEL, MIN_FREE_MB, RELEASE_ON_PRESSURE, DESCRIBE_SIZE)
}

object AccelerationSettings {
    val PROCESSOR_ONLY = Toggle(
        "accel.processor_only", false, ACCELERATION, "Processor only",
        "Every model runs on the processor and no GPU or accelerator driver is used. Overrides the " +
            "choices below.",
    )
    private const val HARDWARE_NOTE = "The accelerator is the phone's NPU or GPU through Android's " +
        "NNAPI, where the phone provides one. A model that cannot run there falls back to the processor."
    val VISUAL = Pick(
        "accel.visual", ModelHardware.CPU, ACCELERATION, "Visual search model runs on", HARDWARE_NOTE,
        ModelHardware.entries, ModelHardware::label,
    )
    val DESCRIBE = Pick(
        "accel.describe", ModelHardware.CPU, ACCELERATION, "Describe model runs on", HARDWARE_NOTE,
        ModelHardware.entries, ModelHardware::label,
    )
    val MEANING = Pick(
        "accel.meaning", ModelHardware.CPU, ACCELERATION, "Meaning model runs on", HARDWARE_NOTE,
        ModelHardware.entries, ModelHardware::label,
    )
    val TEXT_READING = Pick(
        "accel.ocr", ModelHardware.CPU, ACCELERATION, "Text reading runs on", HARDWARE_NOTE,
        ModelHardware.entries, ModelHardware::label, advanced = true,
    )
    val GPU_NATIVE = Toggle(
        "accel.gpu_native", true, ACCELERATION, "Speech and judge models use the GPU",
        "Runs them through Vulkan. Off runs them on the processor.",
    )
    val SOFTWARE_SCREENS = Toggle(
        "accel.software_screens", false, ACCELERATION, "Draw screens without the GPU",
        "Renders the app's screens on the processor. Slower; for phones whose graphics driver shows " +
            "glitches. Applies when a screen next opens.",
    )
    val all = listOf(PROCESSOR_ONLY, VISUAL, DESCRIBE, MEANING, TEXT_READING, GPU_NATIVE, SOFTWARE_SCREENS)

    /** Where a model of this family runs, with [PROCESSOR_ONLY] applied. */
    fun hardwareFor(family: Pick<ModelHardware>): ModelHardware =
        if (Settings[PROCESSOR_ONLY]) ModelHardware.CPU else Settings[family]

    fun nativeGpu(): Boolean = !Settings[PROCESSOR_ONLY] && Settings[GPU_NATIVE]
}

object OpeningSettings {
    val TAP = Pick(
        "open.tap", TapAction.COPY, OPENING, "Tapping a result",
        "What a tap on a search result does. Holding a result always shows the preview.",
        TapAction.entries, TapAction::label,
    )
    private const val OTHER_APPS = "The built-in player plays inside the preview, and the open button uses " +
        "the default app. Another app may start from the beginning."
    val VIDEO = Pick("open.video", OpenWith.BUILT_IN, OPENING, "Play videos with", OTHER_APPS, OpenWith.entries, OpenWith::label)
    val VIDEO_APP = Words("open.video_app", "", OPENING, "Video app", "Used when videos play with a chosen app.", appsFor = "video/*")
    val AUDIO = Pick("open.audio", OpenWith.BUILT_IN, OPENING, "Play recordings with", OTHER_APPS, OpenWith.entries, OpenWith::label)
    val AUDIO_APP = Words("open.audio_app", "", OPENING, "Audio app", "Used when recordings play with a chosen app.", appsFor = "audio/*")
    val PHOTO = Pick(
        "open.photo", OpenWith.DEFAULT, OPENING, "Open photos with",
        "The app the open button shows a photo in. Holding a result still shows the preview.",
        listOf(OpenWith.DEFAULT, OpenWith.ASK, OpenWith.APP), OpenWith::label,
    )
    val PHOTO_APP = Words("open.photo_app", "", OPENING, "Photo app", "Used when photos open with a chosen app.", appsFor = "image/*")
    val FROM_MOMENT = Toggle(
        "open.from_moment", true, OPENING, "Start at the matching moment",
        "Videos and recordings open where the match was found, when it is known.",
    )
    val all = listOf(TAP, VIDEO, VIDEO_APP, AUDIO, AUDIO_APP, PHOTO, PHOTO_APP, FROM_MOMENT)
}

object ScheduleSettings {
    val EVERY_HOURS = Amount(
        "schedule.every_h", -1.0, SCHEDULE, "Index automatically every",
        "Runs indexing on a timer. −1 turns this off.",
        min = 1.0, max = 72.0, unit = "h", minusOne = MinusOne.OFF,
    )
    val ON_NEW_MEDIA = Toggle(
        "schedule.on_new_media", false, SCHEDULE, "Index new media",
        "Starts indexing shortly after new photos, videos or recordings appear.",
    )
    val ON_CHARGER = Toggle(
        "schedule.on_charger", false, SCHEDULE, "Index when charging",
        "Starts indexing when a charger is connected, and checks again every hour while it stays connected.",
    )
    val ON_BOOT = Toggle(
        "schedule.on_boot", true, SCHEDULE, "Resume after restart",
        "Continues an unfinished index after the phone restarts.",
    )
    val IDLE_ONLY = Toggle(
        "schedule.idle_only", false, SCHEDULE, "Wait until the phone is idle",
        "Automatic runs start only while the phone is not in use. Runs you start are not held back.",
    )
    val SWEEP_NEW = Toggle(
        "schedule.sweep_new_media", false, SCHEDULE, "Hide new private files right away",
        "New files in folders set to hide automatically move to the vault as soon as they appear, even " +
            "while automatic indexing is off. Otherwise they move at the next index.",
    )
    val all = listOf(EVERY_HOURS, ON_NEW_MEDIA, ON_CHARGER, ON_BOOT, IDLE_ONLY, SWEEP_NEW)

    fun anyAutomatic(): Boolean =
        Settings[EVERY_HOURS] > 0 || Settings[ON_NEW_MEDIA] || Settings[ON_CHARGER]
}

/** Which media is indexed at all, and which folders go first. */
object ScopeSettings {
    private val S = SettingSection.SCOPE
    val SKIP_FOLDERS = Words(
        "scope.skip_folders", "", S, "Folders to skip",
        "Media in these folders is not indexed.",
        multiline = true, folders = true,
    )
    val FIRST_FOLDERS = Words(
        "scope.first_folders", "", S, "Folders to index first",
        "Each step of indexing reaches media in these folders before the rest.",
        multiline = true, folders = true,
    )
    val SKIP_TYPES = Words(
        "scope.skip_types", "", S, "File types to skip",
        "MIME types separated by commas; one ending in /* covers a whole kind.",
    )
    val MIN_IMAGE_EDGE = Amount(
        "scope.min_image_px", -1.0, S, "Skip images smaller than",
        "Images whose longer side is under this are not indexed. −1 indexes every size.",
        min = 16.0, max = 4096.0, unit = "px", minusOne = MinusOne.OFF,
    )
    val MIN_VIDEO_S = Amount(
        "scope.min_video_s", -1.0, S, "Skip videos shorter than",
        "−1 indexes every length.",
        min = 1.0, max = 3600.0, unit = "s", minusOne = MinusOne.OFF,
    )
    val MIN_AUDIO_S = Amount(
        "scope.min_audio_s", -1.0, S, "Skip recordings shorter than",
        "−1 indexes every length.",
        min = 1.0, max = 3600.0, unit = "s", minusOne = MinusOne.OFF,
    )
    val all = listOf(SKIP_FOLDERS, FIRST_FOLDERS, SKIP_TYPES, MIN_IMAGE_EDGE, MIN_VIDEO_S, MIN_AUDIO_S)

    fun lines(text: String): List<String> = text.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()

    /** Lower-cased names of the folders to index first. */
    fun firstFolders(): List<String> = lines(Settings[FIRST_FOLDERS])

    /** Whether a file is indexed, from what MediaStore says about it; unknown facts never exclude it. */
    fun includes(kind: MediaKind, folder: String?, mime: String?, width: Int?, height: Int?, durationMs: Long?): Boolean {
        if (folder != null && folder.trim().lowercase() in lines(Settings[SKIP_FOLDERS])) return false
        if (mime != null) {
            val m = mime.lowercase()
            val skipped = Settings[SKIP_TYPES].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (skipped.any { t -> if (t.endsWith("/*")) m.startsWith(t.dropLast(1)) else m == t }) return false
        }
        val edge = maxOf(width ?: 0, height ?: 0)
        when (kind) {
            MediaKind.IMAGE -> Settings.valueOrNull(MIN_IMAGE_EDGE)?.let { if (edge > 0 && edge < it) return false }
            MediaKind.VIDEO -> Settings.valueOrNull(MIN_VIDEO_S)?.let { if (durationMs != null && durationMs > 0 && durationMs < it * 1000) return false }
            MediaKind.AUDIO -> Settings.valueOrNull(MIN_AUDIO_S)?.let { if (durationMs != null && durationMs > 0 && durationMs < it * 1000) return false }
        }
        return true
    }
}

/** Which passes run on which kind of media, and how they read it. */
object MediaSettings {
    private fun pass(kind: String, pass: String, section: SettingSection, title: String, summary: String) =
        Toggle("media.$kind.$pass", true, section, title, summary)

    val PHOTO_TEXT = pass("photo", "ocr", PHOTOS, "Read text", "Text in the image becomes searchable.")
    val PHOTO_DESCRIBE = pass("photo", "describe", PHOTOS, "Describe and name objects", "A caption and the names of what it shows.")
    val PHOTO_FACES = pass("photo", "faces", PHOTOS, "Find faces", "Face counts, and groups of photos of the same person.")
    val PHOTO_VISUAL = pass("photo", "visual", PHOTOS, "Visual search", "Lets a search match what a photo looks like. Concept labels need it.")
    val PHOTO_CONCEPTS = pass("photo", "concepts", PHOTOS, "Concept labels", "Labels read from the visual search vectors, including labels you taught.")
    val PHOTO_MEANING = pass("photo", "meaning", PHOTOS, "Meaning search", "Lets a search match by meaning and across languages.")
    val PHOTO_EDGE = Amount(
        "media.photo.edge", 1024.0, PHOTOS, "Analysis size",
        "Longest edge a photo is scaled to before it is read. Larger reads small text better and takes " +
            "longer. The describe model always uses its own size.",
        min = 512.0, max = 2048.0, unit = "px", advanced = true,
    )

    val VIDEO_DESCRIBE = pass("video", "describe", VIDEOS, "Describe and name objects", "A caption and names, read from one frame of each video.")
    val VIDEO_KEYFRAMES = pass("video", "keyframes", VIDEOS, "Keyframes", "Frames are read for on-screen text and visual search.")
    val VIDEO_SPEECH = pass("video", "speech", VIDEOS, "Transcribe speech", "What is said becomes searchable, with where it is said.")
    val VIDEO_CONCEPTS = pass("video", "concepts", VIDEOS, "Concept labels", "Labels read from the keyframes' visual search vectors.")
    val VIDEO_MEANING = pass("video", "meaning", VIDEOS, "Meaning search", "Lets a search match by meaning and across languages.")
    val VIDEO_FRAMES = Amount(
        "media.video.frames", 20.0, VIDEOS, "Keyframes per video",
        "The most frames read from one video. Applies to videos read from now on.",
        min = 1.0, max = 60.0, advanced = true,
    )
    val VIDEO_FPS = Amount(
        "media.video.fps", 1.0, VIDEOS, "Frames per second",
        "How densely a short video is sampled, up to the keyframe limit.",
        min = 0.1, max = 5.0, decimals = 1, advanced = true,
    )
    val VIDEO_DUPLICATES = Amount(
        "media.video.duplicate_bits", 6.0, VIDEOS, "Skip frames this similar",
        "A frame differing from the previous one in this many of 64 picture bits or fewer is skipped. " +
            "0 keeps every frame.",
        min = 0.0, max = 20.0, advanced = true,
    )
    val VIDEO_DESCRIBE_FRAME = Pick(
        "media.video.describe_frame", DescribeFrame.TYPICAL, VIDEOS, "Describe from",
        "The most typical keyframe is the one most like the rest of the video.",
        DescribeFrame.entries, DescribeFrame::label, advanced = true,
    )

    val AUDIO_SPEECH = pass("audio", "speech", AUDIO, "Transcribe speech", "What is said becomes searchable, with where it is said.")
    val AUDIO_MEANING = pass("audio", "meaning", AUDIO, "Meaning search", "Lets a search match by meaning and across languages.")
    val SPEECH_LANGUAGE = Words(
        "media.speech.language", "", AUDIO, "Speech language",
        "A language code for transcription, for videos too. Empty detects the language.",
        advanced = true,
    )
    val SPEECH_WINDOW = Amount(
        "media.speech.window_s", 60.0, AUDIO, "Meaning search window",
        "Transcripts are searched by meaning in stretches of this length. Applies to media read from now on.",
        min = 15.0, max = 600.0, unit = "s", advanced = true,
    )

    val all = listOf(
        PHOTO_TEXT, PHOTO_DESCRIBE, PHOTO_FACES, PHOTO_VISUAL, PHOTO_CONCEPTS, PHOTO_MEANING, PHOTO_EDGE,
        VIDEO_DESCRIBE, VIDEO_KEYFRAMES, VIDEO_SPEECH, VIDEO_CONCEPTS, VIDEO_MEANING,
        VIDEO_FRAMES, VIDEO_FPS, VIDEO_DUPLICATES, VIDEO_DESCRIBE_FRAME,
        AUDIO_SPEECH, AUDIO_MEANING, SPEECH_LANGUAGE, SPEECH_WINDOW,
    )

    private val switches: Map<Pair<MediaKind, Pass>, Toggle> = mapOf(
        (MediaKind.IMAGE to Pass.OCR) to PHOTO_TEXT,
        (MediaKind.IMAGE to Pass.OBJECTS) to PHOTO_DESCRIBE,
        (MediaKind.IMAGE to Pass.FACES) to PHOTO_FACES,
        (MediaKind.IMAGE to Pass.IMAGE_EMBED) to PHOTO_VISUAL,
        (MediaKind.IMAGE to Pass.CONCEPTS) to PHOTO_CONCEPTS,
        (MediaKind.IMAGE to Pass.TEXT_EMBED) to PHOTO_MEANING,
        (MediaKind.VIDEO to Pass.OBJECTS) to VIDEO_DESCRIBE,
        (MediaKind.VIDEO to Pass.KEYFRAMES) to VIDEO_KEYFRAMES,
        (MediaKind.VIDEO to Pass.TRANSCRIBE) to VIDEO_SPEECH,
        (MediaKind.VIDEO to Pass.CONCEPTS) to VIDEO_CONCEPTS,
        (MediaKind.VIDEO to Pass.TEXT_EMBED) to VIDEO_MEANING,
        (MediaKind.AUDIO to Pass.TRANSCRIBE) to AUDIO_SPEECH,
        (MediaKind.AUDIO to Pass.TEXT_EMBED) to AUDIO_MEANING,
    )

    /** Whether [pass] runs on [kind]; a pass with no switch, such as metadata, always does. */
    fun enabled(kind: MediaKind, pass: Pass): Boolean = switches[kind to pass]?.let { Settings[it] } ?: true
}

object DescribingSettings {
    val GROUNDED_ONLY = Toggle(
        "describe.grounded_only", true, DESCRIBING, "Only name what the caption is about",
        "Keeps an object name only when its box matches a phrase of the caption located in the image, " +
            "so background and parts drop out.",
        advanced = true,
    )
    val GROUNDING_OVERLAP = Amount(
        "describe.grounding_iou", 0.5, DESCRIBING, "Caption match overlap",
        "How much an object's box must overlap a caption phrase's box to count as the same thing, as " +
            "intersection over union.",
        min = 0.1, max = 0.95, decimals = 2, advanced = true,
    )
    val SAME_REGION_OVERLAP = Amount(
        "describe.nms_iou", 0.45, DESCRIBING, "One name per region above overlap",
        "Boxes overlapping more than this are one region, which keeps only its most confident name.",
        min = 0.1, max = 0.95, decimals = 2, advanced = true,
    )
    val all = listOf(GROUNDED_ONLY, GROUNDING_OVERLAP, SAME_REGION_OVERLAP)
}

object SettingsCatalog {
    val all: List<Setting<*>> by lazy {
        HeatSettings.all + ProcessingSettings.all + MemorySettings.all + AccelerationSettings.all +
            OpeningSettings.all + ScheduleSettings.all + ScopeSettings.all + MediaSettings.all + DescribingSettings.all +
            SearchSettings.all + DeepSearchSettings.all + LearningSettings.all + LabelSettings.all +
            ModelSizeSettings.all + EmbeddingSettings.all + SimilaritySettings.all
    }
}

enum class CaptionUse(val label: String) {
    SENTENCES("Several words, or nothing else matched"),
    ALWAYS("Always"),
    NOTHING_ELSE("Only when nothing else matched"),
    OFF("Never"),
}

enum class PartialMatch(val label: String) {
    WHEN_NOTHING("Only when nothing else matched"), ALWAYS("Always"), NEVER("Never"),
}

enum class MeaningUse(val label: String) {
    ALWAYS("Always"), FEW_MATCHES("When keywords find few"), OFF("Never"),
}

enum class MeaningDims(val label: String, val dims: Int) {
    D128("128", 128), D256("256", 256), D512("512", 512), D768("768 (full)", 768),
}

object SearchSettings {
    private val S = SettingSection.SEARCH
    val KEYWORDS = Toggle(
        "search.keywords", true, S, "Keyword search",
        "Matches the words of names, labels, text read from media, transcripts and places.",
    )
    val CAPTIONS = Pick(
        "search.captions", CaptionUse.SENTENCES, S, "Search captions",
        "A caption also names the background, so it suits queries of several words, which it can match " +
            "whole, better than single words.",
        CaptionUse.entries, CaptionUse::label,
    )
    val CAPTION_PARTIAL = Pick(
        "search.caption_partial", PartialMatch.WHEN_NOTHING, S, "Captions with only some of the words",
        "Whether a caption containing some but not all of the query's words counts as a match.",
        PartialMatch.entries, PartialMatch::label, advanced = true,
    )
    val VISUAL = Toggle(
        "search.visual", true, S, "Visual search",
        "Matches what photos and video frames look like.",
    )
    val MEANING = Pick(
        "search.meaning", MeaningUse.ALWAYS, S, "Meaning search",
        "Matches by meaning and across languages, where no word is shared. It is the slowest kind of match.",
        MeaningUse.entries, MeaningUse::label,
    )
    val FEW_MATCHES = Amount(
        "search.few_matches", 5.0, S, "Few keyword matches is under",
        "For meaning search that runs only when keywords find few results.",
        min = 1.0, max = 100.0, advanced = true,
    )
    val MEANING_FLOOR = Amount(
        "search.meaning_floor", 0.64, SettingSection.EMBEDDING_SEARCH, "Meaning match floor",
        "A query whose best meaning match scores below this gets no meaning results. Unrelated texts " +
            "score just under it with the meaning model's default vector size.",
        min = 0.3, max = 0.95, decimals = 2, advanced = true,
    )
    val FAIR_RANKING = Toggle(
        "search.fair_ranking", true, SettingSection.EMBEDDING_SEARCH, "Fair ranking for long media",
        "Corrects for videos and recordings having many vectors, so they do not match unrelated " +
            "queries by chance, and keeps only results that stand out.",
        advanced = true,
    )
    val TYPOS = Toggle(
        "search.typos", true, S, "Correct typos",
        "When nothing matches, the closest spelling found in the gallery is tried.",
    )
    val HISTORY = Toggle(
        "search.history", true, S, "Learn from what you pick",
        "Results you open, copy or vote for rank higher for the same words.",
    )
    val MAX_RESULTS = Amount(
        "search.max_results", 60.0, S, "Results shown",
        "The most results one search shows.",
        min = 10.0, max = 300.0,
    )
    private const val WEIGHT_NOTE = "How much this kind of match counts when the result lists are merged."
    val KEYWORD_WEIGHT = Amount("search.weight_keywords", 1.6, S, "Keyword weight", WEIGHT_NOTE, min = 0.0, max = 5.0, decimals = 1, advanced = true)
    val CAPTION_WEIGHT = Amount("search.weight_captions", 1.0, S, "Caption weight", WEIGHT_NOTE, min = 0.0, max = 5.0, decimals = 1, advanced = true)
    val VISUAL_WEIGHT = Amount("search.weight_visual", 1.0, S, "Visual weight", WEIGHT_NOTE, min = 0.0, max = 5.0, decimals = 1, advanced = true)
    val MEANING_WEIGHT = Amount("search.weight_meaning", 1.0, S, "Meaning weight", WEIGHT_NOTE, min = 0.0, max = 5.0, decimals = 1, advanced = true)
    val BM25_K1 = Amount(
        "search.bm25_k1", 1.2, S, "Keyword saturation (BM25 k1)",
        "How quickly a word repeated in one text stops adding to its score. 0 counts a word once.",
        min = 0.0, max = 3.0, decimals = 2, advanced = true,
    )
    val BM25_B = Amount(
        "search.bm25_b", 0.75, S, "Length normalisation (BM25 b)",
        "How much a long text is held back against a short one for the same matches. 0 ignores length; " +
            "1 normalises fully.",
        min = 0.0, max = 1.0, decimals = 2, advanced = true,
    )
    val all = listOf(
        KEYWORDS, CAPTIONS, CAPTION_PARTIAL, VISUAL, MEANING, FEW_MATCHES,
        TYPOS, HISTORY, MAX_RESULTS, KEYWORD_WEIGHT, CAPTION_WEIGHT, VISUAL_WEIGHT, MEANING_WEIGHT, BM25_K1, BM25_B,
    )
}

enum class ReviewStyle(val label: String) {
    BY_NAME("One name, many photos"), BY_PHOTO("One photo, rank its names"),
}

enum class RerankWhen(val label: String) {
    OFF("Off"), ON_REQUEST("When I ask"), NO_KEYWORDS("When keywords find nothing"), ALWAYS("Every search"),
}

enum class RerankWith(val label: String) {
    ON_PHONE("On this phone"), ASSISTANT("The review assistant's service"),
}

object DeepSearchSettings {
    private val S = SettingSection.DEEP_SEARCH
    const val QUERY_SLOT = "{query}"
    val WHEN = Pick(
        "deep.when", RerankWhen.OFF, S, "Check results with a vision model",
        "When the vision model looks at the top results. Each result takes seconds on the phone.",
        RerankWhen.entries, RerankWhen::label,
    )
    val WITH = Pick(
        "deep.with", RerankWith.ON_PHONE, S, "Model",
        "On this phone uses the judge model from the Models screen. The review assistant's service is the " +
            "cloud or Ollama service set up for reviewing labels; the results' pictures are sent to it.",
        RerankWith.entries, RerankWith::label,
    )
    val TOP = Amount(
        "deep.top", 8.0, S, "Results checked",
        "How many of the top results the model looks at. The rest keep their order below them.",
        min = 1.0, max = 30.0,
    )
    val PROMPT = Words(
        "deep.prompt",
        "Does this image match the search \"$QUERY_SLOT\"? Reply with one number from 0 to 10, where 0 " +
            "means unrelated and 10 means an exact match.",
        S, "Question asked about each result",
        "$QUERY_SLOT is replaced with the search. The first number in the reply is the score, read on a " +
            "scale of 0 to 10.",
        multiline = true,
    )
    val DROP_BELOW = Amount(
        "deep.drop_below", -1.0, S, "Hide results scored below",
        "Checked results scoring under this are left out. −1 keeps them all.",
        min = 0.0, max = 10.0, minusOne = MinusOne.OFF, decimals = 1,
    )
    val MAX_TOKENS = Amount(
        "deep.max_tokens", 8.0, S, "Reply length",
        "Most tokens the model may write per result. A score needs very few; longer replies take longer.",
        min = 1.0, max = 64.0, advanced = true,
    )
    val IMAGE_EDGE = Amount(
        "deep.image_edge", 448.0, S, "Picture size",
        "Longest edge each result's picture is scaled to before the model sees it. Larger shows more " +
            "detail and takes longer, or costs more with a service.",
        min = 224.0, max = 1024.0, unit = "px", advanced = true,
    )
    val all = listOf(WHEN, WITH, TOP, PROMPT, DROP_BELOW, MAX_TOKENS, IMAGE_EDGE)
}

object LearningSettings {
    private val S = SettingSection.LEARNING
    val REVIEW_STYLE = Pick(
        "learning.review_style", ReviewStyle.BY_NAME, S, "How names are asked about",
        "One name over many photos is fastest. One photo with every name the models gave it, chosen best " +
            "first, also records how the names compare, as group-relative rewards for training.",
        ReviewStyle.entries, ReviewStyle::label,
    )
    val ASK_UNCERTAIN = Toggle(
        "learning.ask_uncertain", false, S, "Ask about uncertain names",
        "Also asks about names the model gave with low confidence. They stay searchable while asked.",
    )
    val UNCERTAIN_BELOW = Amount(
        "learning.uncertain_below", 0.40, S, "Uncertain below",
        "Confidence under which a name counts as uncertain, until your answers set a line for that name.",
        min = 0.05, max = 0.95, decimals = 2, advanced = true,
    )
    val DISAGREE_RANK = Amount(
        "learning.disagree_rank", 3.0, S, "Models disagree beyond rank",
        "CLIP, shown the object's box, must rank the name within this many places among every name in " +
            "the index, or the name is asked about.",
        min = 1.0, max = 20.0, advanced = true,
    )
    val OUTLIER_Z = Amount(
        "learning.outlier_z", 2.5, S, "Outlier distance",
        "How far from the other photos with the same name a photo must look to be asked about, in robust " +
            "standard deviations.",
        min = 1.0, max = 5.0, decimals = 1, advanced = true,
    )
    val OUTLIER_SUPPORT = Amount(
        "learning.outlier_support", 8.0, S, "Outliers need at least",
        "Photos with a name before any of them can be an outlier.",
        min = 3.0, max = 100.0, advanced = true,
    )
    val WHOLE_FRAME = Amount(
        "learning.whole_frame", 0.90, S, "Whole-photo box from",
        "Share of the image a box must cover to be asked about: an object the model has no name for " +
            "that fills the frame is forced onto the nearest name.",
        min = 0.5, max = 1.0, decimals = 2, advanced = true,
    )
    val MIN_ANSWERS = Amount(
        "learning.min_answers", 5.0, S, "Answers needed to learn a name",
        "Yes answers and no answers each needed before your answers on a name predict the rest.",
        min = 2.0, max = 50.0, advanced = true,
    )
    val MIN_ACCURACY = Amount(
        "learning.min_accuracy", 0.75, S, "Learning accuracy floor",
        "With each answer held out in turn, the prediction must get yes and no right this often on average.",
        min = 0.5, max = 1.0, decimals = 2, advanced = true,
    )
    val SHUFFLES = Amount(
        "learning.shuffles", 19.0, S, "Chance test runs",
        "The prediction must beat this many refits on shuffled answers. 19 is a 5% chance test, 99 a 1% one.",
        min = 9.0, max = 199.0, advanced = true,
    )
    val CONFIDENT_YES = Amount(
        "learning.confident_yes", 0.9, S, "Confident yes from",
        "A predicted yes at least this sure drops the question.",
        min = 0.5, max = 1.0, decimals = 2, advanced = true,
    )
    val NEIGHBOURS = Amount(
        "learning.neighbours", 3.0, S, "Similar regions compared",
        "How many of the most similar answered regions each prediction weighs.",
        min = 1.0, max = 15.0, advanced = true,
    )
    val GROUP_SIZE = Amount(
        "learning.group_size", 24.0, S, "Photos per question",
        "The most photos one question shows.",
        min = 4.0, max = 60.0, advanced = true,
    )
    private const val REWARD_NOTE = "Reward this action gives a result for the words searched. Rewards add " +
        "up per result and word, within bounds."
    val REWARD_UP = Amount("learning.reward_up", 4.0, S, "Reward for voting up", REWARD_NOTE, min = 0.0, max = 20.0, decimals = 2, advanced = true)
    val REWARD_DOWN = Amount("learning.reward_down", -4.0, S, "Reward for voting down", REWARD_NOTE, min = -20.0, max = 0.0, decimals = 2, advanced = true)
    val REWARD_COPY = Amount("learning.reward_copy", 2.0, S, "Reward for copying", REWARD_NOTE, min = 0.0, max = 20.0, decimals = 2, advanced = true)
    val REWARD_OPEN = Amount("learning.reward_open", 1.0, S, "Reward for opening", REWARD_NOTE, min = 0.0, max = 20.0, decimals = 2, advanced = true)
    val REWARD_PREVIEW = Amount("learning.reward_preview", 0.25, S, "Reward for previewing", REWARD_NOTE, min = 0.0, max = 20.0, decimals = 2, advanced = true)
    val REWARD_SKIP = Amount(
        "learning.reward_skip", -0.25, S, "Reward for passing over",
        "Given to results shown above the one you picked.",
        min = -20.0, max = 0.0, decimals = 2, advanced = true,
    )
    val PICK_INFLUENCE = Amount(
        "learning.pick_influence", 0.35, S, "Most a pick history can move a result",
        "The largest share by which rewards raise or lower a result's score.",
        min = 0.0, max = 1.0, decimals = 2, advanced = true,
    )
    val all = listOf(
        REVIEW_STYLE, ASK_UNCERTAIN, UNCERTAIN_BELOW, DISAGREE_RANK, OUTLIER_Z, OUTLIER_SUPPORT, WHOLE_FRAME, MIN_ANSWERS,
        MIN_ACCURACY, SHUFFLES, CONFIDENT_YES, NEIGHBOURS, GROUP_SIZE,
        REWARD_UP, REWARD_DOWN, REWARD_COPY, REWARD_OPEN, REWARD_PREVIEW, REWARD_SKIP, PICK_INFLUENCE,
    )
}

object LabelSettings {
    private val S = SettingSection.LABELS
    val RULES = Words(
        "labels.rules", "", S, "Label rules",
        "Rename or merge a label everywhere, or hide it from search and questions. Applies as the index " +
            "next rebuilds its search text.",
        multiline = true, labelRules = true,
    )
    private fun source(key: String, title: String, summary: String) =
        Toggle("labels.search_$key", true, S, title, summary)
    val OBJECT_NAMES = source("objects", "Object names in search", "Names from the describe model.")
    val CONCEPTS = source("concepts", "Concept labels in search", "Labels read from the visual search vectors.")
    val LEARNED = source("learned", "Learned labels in search", "Labels applied because a photo looks like ones you labelled.")
    val PEOPLE = source("people", "People tags in search", "Face counts and the names you gave people.")
    val TEXT_KEYWORDS = source("text_keywords", "Keywords from text in search", "Distinctive words picked from text read off images.")
    val CATEGORIES = source("categories", "Categories in search", "The broad kind of scene or media.")
    val all = listOf(RULES, OBJECT_NAMES, CONCEPTS, LEARNED, PEOPLE, TEXT_KEYWORDS, CATEGORIES)

    /** Whether labels from [source] reach search; sources with no switch, such as yours, always do. */
    fun searches(source: ai.dusty.finderplus.model.TagSource): Boolean = when (source) {
        ai.dusty.finderplus.model.TagSource.OBJECT -> Settings[OBJECT_NAMES]
        ai.dusty.finderplus.model.TagSource.CONCEPT -> Settings[CONCEPTS]
        ai.dusty.finderplus.model.TagSource.LEARNED -> Settings[LEARNED]
        ai.dusty.finderplus.model.TagSource.FACE -> Settings[PEOPLE]
        ai.dusty.finderplus.model.TagSource.OCR_KEYWORD -> Settings[TEXT_KEYWORDS]
        ai.dusty.finderplus.model.TagSource.CATEGORY -> Settings[CATEGORIES]
        else -> true
    }
}

object EmbeddingSettings {
    private val S = SettingSection.EMBEDDING_SEARCH
    val STANDOUT = Amount(
        "embedding.standout", 1.0, S, "Stand-out strictness",
        "A visual or meaning match is kept only when it stands out from how every item scored for the " +
            "query, by about as much as the best of that many unrelated items would by chance. This scales " +
            "that line: lower keeps weaker matches, 0 keeps everything above average.",
        min = 0.0, max = 3.0, decimals = 2, advanced = true,
    )
    val MANY_VECTOR_CORRECTION = Amount(
        "embedding.lift", 1.0, S, "Many-vector correction",
        "How much a video or recording is held back for having many vectors, each a chance to look like " +
            "the query. 0 judges it by its best vector alone.",
        min = 0.0, max = 2.0, decimals = 2, advanced = true,
    )
    val VISUAL_FLOOR = Amount(
        "embedding.visual_floor", -1.0, S, "Visual match floor",
        "Visual matches whose cosine with the query is below this are dropped before anything else. A " +
            "clear text-to-picture match sits around 0.3 and an unrelated one near 0.2. −1 relies on " +
            "stand-out strictness alone.",
        min = 0.0, max = 1.0, minusOne = MinusOne.OFF, decimals = 3, advanced = true,
    )
    val VISUAL_LIMIT = Amount(
        "embedding.visual_limit", 100.0, S, "Visual matches merged",
        "The most visual matches carried into merging.",
        min = 10.0, max = 1000.0, advanced = true,
    )
    val MEANING_LIMIT = Amount(
        "embedding.meaning_limit", 120.0, S, "Meaning matches merged",
        "The most meaning matches carried into merging.",
        min = 10.0, max = 1000.0, advanced = true,
    )
    val KEYWORD_LIMIT = Amount(
        "embedding.keyword_limit", 200.0, S, "Keyword matches merged",
        "The most keyword and caption matches carried into merging, best first.",
        min = 10.0, max = 2000.0, advanced = true,
    )
    val RANK_DAMPING = Amount(
        "embedding.rrf_k", 60.0, S, "Rank damping (RRF k)",
        "Merging scores each match by its place in its own list. Larger lets lower places count for " +
            "more; smaller lets the top of each list decide.",
        min = 1.0, max = 500.0, advanced = true,
    )
    val AGREEMENT = Amount(
        "embedding.agreement", 0.25, S, "Agreement bonus",
        "How much an item found by several kinds of match is raised, for each kind beyond the first.",
        min = 0.0, max = 2.0, decimals = 2, advanced = true,
    )
    val all = listOf(
        SearchSettings.FAIR_RANKING, STANDOUT, MANY_VECTOR_CORRECTION, VISUAL_FLOOR, SearchSettings.MEANING_FLOOR,
        VISUAL_LIMIT, MEANING_LIMIT, KEYWORD_LIMIT, RANK_DAMPING, AGREEMENT,
    )
}

object SimilaritySettings {
    private val S = SettingSection.SIMILARITY
    val LEARNED_SUGGEST = Amount(
        "similarity.learned_suggest", 0.78, S, "Learned label suggested from",
        "Cosine between a photo and the photos you gave a label at which the label is suggested for it. " +
            "A label taught on few photos needs more.",
        min = 0.5, max = 0.99, decimals = 2, advanced = true,
    )
    val LEARNED_APPLY = Amount(
        "similarity.learned_apply", 0.90, S, "Learned label applied from",
        "At or above this the learned label is applied without asking.",
        min = 0.5, max = 1.0, decimals = 2, advanced = true,
    )
    val CONCEPT_APPLY = Amount(
        "similarity.concept_apply", 0.35, S, "Concept label applied from",
        "Confidence at which a concept label is applied without asking.",
        min = 0.05, max = 1.0, decimals = 2, advanced = true,
    )
    val CONCEPT_ASK = Amount(
        "similarity.concept_ask", 0.12, S, "Concept label asked about from",
        "Below the line above but at or over this, the concept label is asked about; lower is dropped.",
        min = 0.01, max = 1.0, decimals = 2, advanced = true,
    )
    val ENTITY = Amount(
        "similarity.entity", 0.28, S, "Named place or thing from",
        "Cosine between a picture and a named place or thing's text before it may be named at all.",
        min = 0.1, max = 0.5, decimals = 3, advanced = true,
    )
    val SAME_THING = Amount(
        "similarity.same_thing", 0.82, S, "Same thing",
        "Cosine at which photos are grouped as showing the same thing, so one answer names the whole " +
            "group. Groups chain, so a little lower can merge unrelated groups into one.",
        min = 0.5, max = 0.99, decimals = 2, advanced = true,
    )
    val SAME_PERSON = Amount(
        "similarity.same_person", 0.60, S, "Same person",
        "Cosine between face embeddings at which two faces are grouped as one person. Higher splits " +
            "one person into several groups; lower merges different people.",
        min = 0.3, max = 0.95, decimals = 2, advanced = true,
    )
    val LOOK_ALIKE = Amount(
        "similarity.look_alike", 0.96, S, "Look-alike photos",
        "Cosine at which Clean up offers photos as look-alikes of each other.",
        min = 0.8, max = 0.999, decimals = 3, advanced = true,
    )
    val all = listOf(LEARNED_SUGGEST, LEARNED_APPLY, CONCEPT_APPLY, CONCEPT_ASK, ENTITY, SAME_THING, SAME_PERSON, LOOK_ALIKE)
}

object ModelSizeSettings {
    val MEANING_DIMS = Pick(
        "models.meaning_dims", MeaningDims.D256, SettingSection.MODEL_SIZES, "Meaning vector size",
        "The meaning model is trained so its vectors can be cut to any of these sizes. Longer matches a " +
            "little better and costs space and search time. Every item is embedded again at the next " +
            "index; until then search compares the sizes it has. Visual search vectors have the one size " +
            "their model was trained with.",
        MeaningDims.entries, MeaningDims::label, advanced = true,
    )
    val all = listOf(MEANING_DIMS)
}

/**
 * Rules the user wrote for labels, one per line: `name -> new name` renames or merges, `name ->` with
 * nothing after hides. Names compare without case.
 */
object LabelRules {
    const val ARROW = "->"

    fun parse(text: String): Map<String, String?> = text.lineSequence()
        .mapNotNull { line ->
            val i = line.indexOf(ARROW)
            if (i <= 0) return@mapNotNull null
            val from = line.substring(0, i).trim().lowercase()
            val to = line.substring(i + ARROW.length).trim()
            if (from.isEmpty()) null else from to to.ifEmpty { null }
        }
        .toMap()

    fun format(rules: Map<String, String?>): String =
        rules.entries.joinToString("\n") { (from, to) -> "$from $ARROW ${to.orEmpty()}".trimEnd() }

    fun current(): Map<String, String?> = parse(Settings[LabelSettings.RULES])

    /** [label] after the rules: renamed, unchanged, or null when hidden. */
    fun apply(label: String, rules: Map<String, String?> = current()): String? {
        val key = label.trim().lowercase()
        if (!rules.containsKey(key)) return label
        return rules[key]
    }
}

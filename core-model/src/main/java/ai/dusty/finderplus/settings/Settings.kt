package ai.dusty.finderplus.settings

/** Where a setting is shown; the order here is the order on screen. */
enum class SettingSection(val title: String, val blurb: String) {
    HEAT("🌡️ Heat limits", "When indexing stops to let the phone cool."),
    PROCESSING("⚙️ Processing", "How hard indexing works, and when."),
    MEMORY("🧠 Memory", "How much memory the models may hold."),
    ACCELERATION("⚡ Acceleration", "Which hardware runs the models and draws the screens."),
    OPENING("📂 Opening results", "What opens a photo, video or recording."),
    SCHEDULE("⏰ When indexing runs", "Automatic runs, the events that start them, and the private-folder sweep."),
    SCOPE(
        "📁 Folders and files",
        "Which media is indexed, and which first. Applies at the next index; what was read from media " +
            "that is now skipped leaves the index.",
    ),
    PHOTOS("🖼️ Photos", "What is read from each photo. Changes apply from the next index; what was already read stays."),
    VIDEOS("🎬 Videos", "What is read from each video. Changes apply from the next index; what was already read stays."),
    AUDIO("🎧 Audio", "What is read from each recording. Changes apply from the next index; what was already read stays."),
    DESCRIBING("🏷️ Describing", "How the describe model's names are kept or dropped."),
    SEARCH("🔍 Search", "Which kinds of match a search uses, and how they are ranked and merged."),
    DEEP_SEARCH(
        "🔬 Deep search",
        "A vision-language model looks at the top results and puts them in order of how well they fit. " +
            "Slow, so it runs only when chosen.",
    ),
    LEARNING("🎓 Review and learning", "When names are asked about, and how answers and picks teach the app."),
    LABELS("🔖 Labels", "Which labels search uses, and rules that rename or hide them."),
    MODEL_SIZES("📐 Model sizes", "Vector sizes, where a model allows a choice."),
    EMBEDDING_SEARCH(
        "🧭 Embedding search",
        "How visual and meaning matches are judged before they are merged with the rest.",
    ),
    SIMILARITY(
        "🔗 Similarity thresholds",
        "How alike two things must be, by cosine similarity, before the app treats them as the same. " +
            "Labels already written are not revisited.",
    ),
}

/** What −1 means for an [Amount]. */
enum class MinusOne { NOT_ALLOWED, OFF, AUTO }

/**
 * One user setting: its stored key, its default, and how it is presented. The default is always the
 * behaviour the app had before the setting existed.
 */
sealed class Setting<T : Any>(
    val key: String,
    val default: T,
    val section: SettingSection,
    val title: String,
    val summary: String,
    /** Shown only with the advanced switch on. */
    val advanced: Boolean,
)

class Toggle(
    key: String, default: Boolean, section: SettingSection, title: String, summary: String,
    advanced: Boolean = false,
) : Setting<Boolean>(key, default, section, title, summary, advanced)

/** A number within [min]..[max]. Depending on [minusOne], −1 also means "off" or "the app decides". */
class Amount(
    key: String, default: Double, section: SettingSection, title: String, summary: String,
    val min: Double,
    val max: Double,
    val unit: String = "",
    val minusOne: MinusOne = MinusOne.NOT_ALLOWED,
    val decimals: Int = 0,
    advanced: Boolean = false,
) : Setting<Double>(key, default, section, title, summary, advanced) {
    fun accepts(value: Double): Boolean =
        (value == -1.0 && minusOne != MinusOne.NOT_ALLOWED) || value in min..max
}

class Pick<E : Enum<E>>(
    key: String, default: E, section: SettingSection, title: String, summary: String,
    val options: List<E>,
    val label: (E) -> String,
    advanced: Boolean = false,
) : Setting<E>(key, default, section, title, summary, advanced) {
    fun parse(name: String?): E = options.firstOrNull { it.name == name } ?: default

    /** The options as (name, label), for screens that hold the setting without its type. */
    fun choices(): List<Pair<String, String>> = options.map { it.name to label(it) }

    fun currentName(store: SettingsStore): String = store.get(this).name

    fun selectByName(store: SettingsStore, name: String) = store.set(this, parse(name))

    fun resetIn(store: SettingsStore) = store.set(this, default)
}

/**
 * Free text. With [appsFor] set, it holds the package of an app that opens that media type; with
 * [labelRules], one [LabelRules] rule per line; with [folders], one folder name per line.
 */
class Words(
    key: String, default: String, section: SettingSection, title: String, summary: String,
    val multiline: Boolean = false,
    val appsFor: String? = null,
    val labelRules: Boolean = false,
    val folders: Boolean = false,
    advanced: Boolean = false,
) : Setting<String>(key, default, section, title, summary, advanced)

interface SettingsStore {
    fun <T : Any> get(setting: Setting<T>): T
    fun <T : Any> set(setting: Setting<T>, value: T)
}

/** Reads every setting as its default. */
object DefaultsStore : SettingsStore {
    override fun <T : Any> get(setting: Setting<T>): T = setting.default
    override fun <T : Any> set(setting: Setting<T>, value: T) {}
}

/** Settings held in memory, for tests. */
class MemoryStore : SettingsStore {
    private val values = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(setting: Setting<T>): T = values[setting.key] as? T ?: setting.default
    override fun <T : Any> set(setting: Setting<T>, value: T) { values[setting.key] = value }
}

/**
 * The current settings, readable from any module. The app installs its persistent [store] when it
 * starts; until then, and in tests, every setting reads its default.
 */
object Settings {
    @Volatile var store: SettingsStore = DefaultsStore

    operator fun <T : Any> get(setting: Setting<T>): T =
        runCatching { store.get(setting) }.getOrDefault(setting.default)

    operator fun <T : Any> set(setting: Setting<T>, value: T) = store.set(setting, value)

    /** An [Amount]'s value, or null when it is −1 (off, or left to the app). */
    fun valueOrNull(setting: Amount): Double? = get(setting).takeIf { it != -1.0 }
}

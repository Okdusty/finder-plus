package ai.dusty.finderplus.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings kept in SharedPreferences. Only values the user changed are stored, so a default that
 * changes in a later version reaches everyone who never touched it. A stored value outside its
 * setting's range reads as the default.
 */
class PrefsSettingsStore(context: Context) : SettingsStore {

    private val sp = context.applicationContext.getSharedPreferences("finder-settings", Context.MODE_PRIVATE)

    private val revision = MutableStateFlow(0L)

    /** Bumped on every change, for screens that show the values. */
    val changes: StateFlow<Long> get() = revision

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(setting: Setting<T>): T = when (setting) {
        is Toggle -> sp.getBoolean(setting.key, setting.default) as T
        is Amount -> (sp.getString(setting.key, null)?.toDoubleOrNull()?.takeIf(setting::accepts) ?: setting.default) as T
        is Pick<*> -> setting.parse(sp.getString(setting.key, null)) as T
        is Words -> (sp.getString(setting.key, null) ?: setting.default) as T
    }

    override fun <T : Any> set(setting: Setting<T>, value: T) {
        val e = sp.edit()
        if (value == setting.default) e.remove(setting.key) else when (setting) {
            is Toggle -> e.putBoolean(setting.key, value as Boolean)
            is Amount -> e.putString(setting.key, (value as Double).toString())
            is Pick<*> -> e.putString(setting.key, (value as Enum<*>).name)
            is Words -> e.putString(setting.key, value as String)
        }
        e.apply()
        revision.value++
    }

    fun isChanged(setting: Setting<*>): Boolean = sp.contains(setting.key)

    /** The settings the user changed, as text, for backups. */
    fun exportChanged(): Map<String, String> = SettingsCatalog.all.filter(::isChanged).associate { setting ->
        setting.key to when (setting) {
            is Toggle -> get(setting).toString()
            is Amount -> get(setting).toString()
            is Pick<*> -> setting.currentName(this)
            is Words -> get(setting)
        }
    }

    /** Settings from [exportChanged]; unknown keys and values a setting does not accept are skipped. */
    fun importValues(values: Map<String, String>): Int {
        var n = 0
        for (setting in SettingsCatalog.all) {
            val v = values[setting.key] ?: continue
            val ok = when (setting) {
                is Toggle -> v.toBooleanStrictOrNull()?.let { set(setting, it) } != null
                is Amount -> v.toDoubleOrNull()?.takeIf(setting::accepts)?.let { set(setting, it) } != null
                is Pick<*> -> setting.choices().any { it.first == v }.also { if (it) setting.selectByName(this, v) }
                is Words -> { set(setting, v); true }
            }
            if (ok) n++
        }
        return n
    }

    /** Every setting back to its default. */
    fun resetAll() {
        sp.edit().clear().apply()
        revision.value++
    }
}

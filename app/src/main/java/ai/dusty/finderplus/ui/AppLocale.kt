package ai.dusty.finderplus.ui

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * finder+ is English-only, whatever the phone's language.
 *
 * Otherwise number grouping, decimals, month names and library strings (dialog buttons, accessibility
 * labels) follow the system language and leave the app half-translated. The locale is pinned in three
 * places to cover every release: the per-app language (Android 13+, also shown in Settings > App
 * languages), the process default used by format calls, and each activity's resources.
 */
object AppLocale {

    val ENGLISH: Locale = Locale.US

    /** Process-wide; call from Application.onCreate and onConfigurationChanged. */
    fun enforce(app: Application) {
        Locale.setDefault(ENGLISH)
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val lm = app.getSystemService(LocaleManager::class.java)
                val en = LocaleList(ENGLISH)
                if (lm.applicationLocales.toLanguageTags() != en.toLanguageTags()) lm.applicationLocales = en
            }
        }
    }

    /** For attachBaseContext: resources and Compose's configuration resolve in English on any release. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration).apply {
            setLocale(ENGLISH)
            setLayoutDirection(ENGLISH)
        }
        return base.createConfigurationContext(config)
    }
}

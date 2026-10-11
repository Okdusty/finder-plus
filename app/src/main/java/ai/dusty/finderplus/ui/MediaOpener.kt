package ai.dusty.finderplus.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.settings.OpenWith
import ai.dusty.finderplus.settings.OpeningSettings
import ai.dusty.finderplus.settings.Settings

/** Opens media in the app the "Opening results" settings choose. */
object MediaOpener {

    /** Installed apps that open [mime], as (package, label), by label. */
    fun appsFor(context: Context, mime: String): List<Pair<String, String>> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://media/external/file/1"), mime)
        return pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private fun choiceFor(mime: String?): Pair<OpenWith, String> = when {
        mime?.startsWith("video/") == true -> Settings[OpeningSettings.VIDEO] to Settings[OpeningSettings.VIDEO_APP]
        mime?.startsWith("audio/") == true -> Settings[OpeningSettings.AUDIO] to Settings[OpeningSettings.AUDIO_APP]
        else -> Settings[OpeningSettings.PHOTO] to Settings[OpeningSettings.PHOTO_APP]
    }

    /** Whether the preview's play button plays [kind] in place rather than handing it to an app. */
    fun playsBuiltIn(kind: MediaKind): Boolean = when (kind) {
        MediaKind.VIDEO -> Settings[OpeningSettings.VIDEO] == OpenWith.BUILT_IN
        MediaKind.AUDIO -> Settings[OpeningSettings.AUDIO] == OpenWith.BUILT_IN
        MediaKind.IMAGE -> false
    }

    /** Where playback should start, or null to start at the beginning. */
    fun startAt(matchMs: Long?): Long? = matchMs?.takeIf { Settings[OpeningSettings.FROM_MOMENT] }

    /**
     * Opens [uri] outside the app. Players that accept a start time get [seekMs] under the extra name
     * the common ones read; others start from the beginning.
     */
    fun open(activity: Activity, uri: Uri, mime: String?, seekMs: Long?) {
        val (choice, pkg) = choiceFor(mime)
        val view = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uri.scheme == "content" && mime != null) view.setDataAndType(uri, mime) else view.data = uri
        startAt(seekMs)?.let { ms ->
            // VLC reads a long, most others an int.
            if (pkg.contains("videolan")) view.putExtra("position", ms) else view.putExtra("position", ms.toInt())
        }
        val chooser = Intent.createChooser(view, "Open with")
        val intent = when (choice) {
            OpenWith.ASK -> chooser
            OpenWith.APP -> {
                val direct = Intent(view).setPackage(pkg)
                if (pkg.isNotEmpty() && direct.resolveActivity(activity.packageManager) != null) direct else chooser
            }
            // The built-in player plays only inside the preview; opening elsewhere uses the default app.
            OpenWith.BUILT_IN, OpenWith.DEFAULT -> view
        }
        activity.startActivity(intent)
    }
}

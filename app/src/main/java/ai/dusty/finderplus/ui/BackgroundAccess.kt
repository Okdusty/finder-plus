package ai.dusty.finderplus.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Exemption from battery optimization, which indexing needs: a run takes hours of CPU, and an
 * optimized app is held to short background windows, kept off the fast cores and frozen between them.
 */
object BackgroundAccess {

    fun isUnrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Ask the system for the exemption when the app does not have it yet. The system shows its own
     * dialog; when a device does not offer it, the battery-optimization list opens instead.
     *
     * @return true when a request was shown.
     */
    fun request(activity: Activity): Boolean {
        if (isUnrestricted(activity)) return false
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        return runCatching { activity.startActivity(direct) }.isSuccess ||
            runCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess
    }
}

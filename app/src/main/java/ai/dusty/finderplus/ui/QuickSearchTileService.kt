package ai.dusty.finderplus.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import ai.dusty.finderplus.ui.popup.SearchPopupActivity

/**
 * Quick Settings tile → the search pop-up. Two swipes from any screen without touching the launcher,
 * which is the closest Android offers to a global keyboard binding without an accessibility service.
 */
class QuickSearchTileService : TileService() {

    override fun onClick() {
        val intent = Intent(this, SearchPopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

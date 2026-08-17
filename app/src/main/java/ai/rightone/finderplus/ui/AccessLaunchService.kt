package ai.rightone.finderplus.ui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import ai.rightone.finderplus.ui.popup.SearchPopupActivity

/**
 * Launch-only accessibility service, so the system-wide accessibility shortcut (volume-keys hold, or
 * the floating button) opens search from anywhere — including over other apps.
 *
 * The pattern is enable → launch → disable-self: Android's accessibility shortcut *toggles* a service
 * rather than sending it events, so a service that launches on connect and immediately disables itself
 * behaves exactly like a global launch key. Each press re-enables it, it fires once, and it is off
 * again — while enabled-but-idle time is essentially zero.
 *
 * Deliberately declares no window-content access and consumes no events. An accessibility service that
 * can read the screen must justify it; one that cannot, cannot be asked to.
 */
class AccessLaunchService : AccessibilityService() {

    override fun onServiceConnected() {
        startActivity(
            Intent(this, SearchPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        disableSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

package ai.dusty.finderplus.ui

import android.app.Activity
import android.view.View
import ai.dusty.finderplus.settings.AccelerationSettings
import ai.dusty.finderplus.settings.Settings

/** Draws a screen on the processor instead of the GPU when the setting asks for it. */
object ScreenDrawing {
    fun apply(activity: Activity) {
        if (Settings[AccelerationSettings.SOFTWARE_SCREENS]) {
            activity.window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
}

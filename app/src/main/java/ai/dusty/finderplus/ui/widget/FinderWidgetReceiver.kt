package ai.rightone.finderplus.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Hosts [FinderWidget] on the home screen. See docs/ui/WIREFRAMES.md §1. */
class FinderWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinderWidget()
}

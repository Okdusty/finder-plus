package ai.dusty.finderplus.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.dusty.finderplus.db.RunState
import ai.dusty.finderplus.db.dao.IndexRunDao
import ai.dusty.finderplus.db.dao.MediaItemDao
import ai.dusty.finderplus.index.IndexOrchestrator
import ai.dusty.finderplus.index.IndexWorker
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.ui.popup.SearchPopupActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/**
 * Home-screen widget (Glance). Search field opens the pop-up; the update button enqueues an
 * incremental index; the status line reflects the live [IndexProgress]. See docs/ui/WIREFRAMES.md §1.
 */
class FinderWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDeps {
        fun mediaItemDao(): MediaItemDao
        fun indexRunDao(): IndexRunDao
        fun orchestrator(): IndexOrchestrator
        fun perfPrefs(): ai.dusty.finderplus.index.PerfPrefs
    }

    /**
     * Status is read from the **index_run row**, not the in-memory progress flow: slices run in
     * separate worker invocations (and the process can die between them), so process-local state goes
     * stale — the widget was showing a count from a finished slice. The DB is the source of truth.
     * The live flow is used only for the current-phase label, which is cosmetic.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = EntryPointAccessors.fromApplication(context, WidgetDeps::class.java)
        val indexed = runCatching { deps.mediaItemDao().count() }.getOrDefault(0)
        val videos = runCatching { deps.mediaItemDao().countKind(MediaKind.VIDEO.ordinal) }.getOrDefault(0)
        val audio = runCatching { deps.mediaItemDao().countKind(MediaKind.AUDIO.ordinal) }.getOrDefault(0)
        val run = runCatching { deps.indexRunDao().latest() }.getOrNull()
        val live = runCatching { deps.orchestrator().progress().first() }.getOrNull()
        val phase = live?.currentPass?.let { " · " + it.uiLabel() } ?: ""

        val unfinished = run != null && run.finished_at == null
        val done = run?.done_units ?: 0
        val total = run?.total_units ?: 0
        val pct = if (total > 0) done * 100 / total else 0
        val active = unfinished && run?.status != RunState.PAUSED

        val statusLine = when {
            run == null ->
                if (indexed > 0) "%,d indexed".format(indexed) else "Nothing indexed yet"
            unfinished && total == 0 -> "Scanning your gallery…"
            unfinished && run.status == RunState.PAUSED ->
                "Paused at %d%% · cooling down, resumes soon".format(pct)
            unfinished -> "Indexing %,d / %,d · %d%%%s".format(done, total, pct, phase)
            run.status == RunState.STOPPED && total > 0 -> "Stopped at %d%%".format(pct)
            else -> buildString {
                append("%,d indexed".format(indexed))
                val photos = (indexed - videos - audio).coerceAtLeast(0)
                if (photos > 0) append(" · %,d photos".format(photos))
                if (videos > 0) append(" · %,d videos".format(videos))
                if (audio > 0) append(" · %,d audio".format(audio))
            }
        }

        // The pill says what tapping it DOES — "Resume · 76%" instead of a glyph the status line
        // had to explain ("tap ⟳ to resume").
        val resumable = (run?.status == RunState.STOPPED || run?.status == RunState.PAUSED) && pct in 1..99
        val actionLabel = when {
            active -> "Pause"
            resumable -> "Resume · $pct%"
            indexed > 0 -> "Update"
            else -> "Index"
        }

        val perf = deps.perfPrefs()
        provideContent {
            GlanceTheme {
                Content(statusLine, actionLabel, active, perf.useGpu, perf.unrestricted)
            }
        }
    }

    @Composable
    private fun Content(
        statusLine: String,
        actionLabel: String,
        running: Boolean,
        useGpu: Boolean,
        unrestricted: Boolean,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(12.dp),
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The search field look-alike — the widget's reason to exist, so it gets the width.
                Box(
                    modifier = GlanceModifier.defaultWeight()
                        .background(GlanceTheme.colors.surfaceVariant)
                        .cornerRadius(18.dp)
                        .clickable(actionStartActivity<SearchPopupActivity>())
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "Search your gallery…",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    )
                }
                Spacer(GlanceModifier.width(8.dp))
                // One labelled pill instead of a bare glyph: a real button-sized target that says
                // what it does ("Pause" / "Resume · 76%" / "Update").
                Box(
                    modifier = GlanceModifier
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(18.dp)
                        .clickable(
                            if (running) actionRunCallback<StopIndexAction>()
                            else actionRunCallback<UpdateIndexAction>()
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = TextStyle(
                            color = GlanceTheme.colors.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
            }
            Text(
                text = statusLine,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
            // Performance switches. On the widget rather than buried in Settings because they are
            // things you change *while watching the index run*, which is exactly when Settings is the
            // most annoying place for them to be.
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PerfChip(
                    text = if (useGpu) "GPU on" else "GPU off",
                    active = useGpu,
                    action = actionRunCallback<ToggleGpuAction>(),
                )
                Spacer(GlanceModifier.width(6.dp))
                PerfChip(
                    text = if (unrestricted) "Full speed" else "Eco",
                    active = unrestricted,
                    action = actionRunCallback<ToggleUnrestrictedAction>(),
                )
            }
        }
    }

    /** Small stateful toggle chip: container colour carries on/off, words carry the meaning. */
    @Composable
    private fun PerfChip(text: String, active: Boolean, action: Action) {
        Box(
            modifier = GlanceModifier
                .background(
                    if (active) GlanceTheme.colors.secondaryContainer
                    else GlanceTheme.colors.surfaceVariant
                )
                .cornerRadius(12.dp)
                .clickable(action)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = text,
                style = TextStyle(
                    color = if (active) GlanceTheme.colors.onSecondaryContainer
                    else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

/** CPU/GPU switch → flips the compute backend for the next model load. */
class ToggleGpuAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val deps = EntryPointAccessors.fromApplication(context, FinderWidget.WidgetDeps::class.java)
        deps.perfPrefs().toggleGpu()
        FinderWidget().update(context, glanceId)
    }
}

/** Full speed/Eco switch → drops the battery and thermal duty-cycling short of CRITICAL. */
class ToggleUnrestrictedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val deps = EntryPointAccessors.fromApplication(context, FinderWidget.WidgetDeps::class.java)
        deps.perfPrefs().toggleUnrestricted()
        FinderWidget().update(context, glanceId)
    }
}

/** Update button → enqueue an incremental index, then refresh the widget. */
class UpdateIndexAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        IndexWorker.enqueue(context)
        FinderWidget().update(context, glanceId)
    }
}

/** Stop button → cooperative stop + cancel the worker, then refresh. */
class StopIndexAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val deps = EntryPointAccessors.fromApplication(context, FinderWidget.WidgetDeps::class.java)
        deps.orchestrator().requestStop()
        IndexWorker.cancel(context)
        FinderWidget().update(context, glanceId)
    }
}

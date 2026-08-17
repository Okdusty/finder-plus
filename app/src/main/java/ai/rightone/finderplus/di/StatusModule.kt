package ai.rightone.finderplus.di

import android.content.Context
import androidx.glance.appwidget.updateAll
import ai.rightone.finderplus.index.IndexStatusListener
import ai.rightone.finderplus.model.IndexProgress
import ai.rightone.finderplus.ui.widget.FinderWidget
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Pushes indexing progress to the home-screen widget. Without this the widget only refreshed when
 * tapped, so it showed a frozen count ("Indexing 1094 / 19657") long after the run had moved on.
 * Updates are rate-limited because a Glance update re-renders and does IPC to the launcher.
 */
@Module
@InstallIn(SingletonComponent::class)
object StatusModule {

    @Provides
    @Singleton
    fun indexStatusListener(@ApplicationContext context: Context): IndexStatusListener =
        WidgetStatusListener(context)
}

private class WidgetStatusListener(private val context: Context) : IndexStatusListener {

    private var lastUpdate = 0L
    private var lastStatus: String? = null

    override suspend fun onProgress(progress: IndexProgress) {
        val now = System.currentTimeMillis()
        val statusKey = progress.status.name
        val statusChanged = statusKey != lastStatus
        if (!statusChanged && now - lastUpdate < MIN_INTERVAL_MS) return
        lastUpdate = now
        lastStatus = statusKey
        runCatching { FinderWidget().updateAll(context) }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 4_000L
    }
}

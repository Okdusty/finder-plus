package ai.dusty.finderplus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point; supplies Hilt's worker factory to WorkManager. The app has no home screen:
 * the widget and the translucent pop-up are its only surfaces. See docs/design/02-ARCHITECTURE.md §5.
 */
@HiltAndroidApp
class FinderApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var coordinator: ai.dusty.finderplus.index.ModelCoordinator
    @Inject lateinit var describer: ai.dusty.finderplus.vision.ImageDescriber
    @Inject lateinit var clipImage: ai.dusty.finderplus.vision.ClipImageEncoder
    @Inject lateinit var speech: ai.dusty.finderplus.speech.SpeechRecognizer
    @Inject lateinit var db: ai.dusty.finderplus.db.FinderDatabase

    /** Stores the diagnostics measurements; flushed when the diagnostics page opens. */
    lateinit var diagnostics: ai.dusty.finderplus.diagnostics.DiagRecorder
        private set

    /** The app's settings; installed before anything, workers included, can read one. */
    lateinit var settings: ai.dusty.finderplus.settings.PrefsSettingsStore
        private set

    override fun onCreate() {
        settings = ai.dusty.finderplus.settings.PrefsSettingsStore(this)
        ai.dusty.finderplus.settings.Settings.store = settings
        super.onCreate()
        ai.dusty.finderplus.ui.AppLocale.enforce(this)
        // Vault decryption is reached from indexing, search, thumbnails and the clipboard, and needs an
        // app context for its wrapped key. Initialise it before any of them can run: a decode before
        // init fails silently and yields no image.
        ai.dusty.finderplus.media.VaultCrypto.init(this)
        // Workers post foreground notifications on this channel; it must exist before the first one
        // runs, which on a fresh install may be a vault or probe worker rather than the indexer.
        ai.dusty.finderplus.index.IndexWorker.ensureChannel(this)
        // Models used only by indexing, under the coordinator's lock, so they can be unloaded safely.
        // The meaning and CLIP text models also serve search and are not unloaded this way.
        coordinator.onUnload(ai.dusty.finderplus.model.RequiredModel.VLM) { describer.unload() }
        coordinator.onUnload(ai.dusty.finderplus.model.RequiredModel.CLIP_IMG) { clipImage.unload() }
        coordinator.onUnload(ai.dusty.finderplus.model.RequiredModel.ASR) { speech.release() }
        ai.dusty.finderplus.index.AutoIndex.apply(this)
        diagnostics = ai.dusty.finderplus.diagnostics.DiagRecorder(db)
        ai.dusty.finderplus.settings.Diagnostics.sink = diagnostics
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hiding the UI is not memory pressure; the rest are, on the Android versions that still send them.
        if (level != TRIM_MEMORY_UI_HIDDEN) releaseModels()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseModels()
    }

    private fun releaseModels() {
        if (ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.MemorySettings.RELEASE_ON_PRESSURE]) {
            coordinator.onPressure()
        }
    }

    /** The framework resets the default locale from the system on a config change; pin it again. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        ai.dusty.finderplus.ui.AppLocale.enforce(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

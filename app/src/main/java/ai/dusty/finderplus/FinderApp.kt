package ai.rightone.finderplus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Provides the Hilt-backed [WorkerFactory] so [IndexWorker] can be injected,
 * and boots the DI graph. There is no launcher "home" screen — the widget and translucent pop-up are
 * the only surfaces. See docs/design/02-ARCHITECTURE.md §5.
 */
@HiltAndroidApp
class FinderApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Vault decryption is reachable from indexing, search, thumbnails and the clipboard, and it
        // needs an app context for the wrapped key. Initialising here rather than at each call site
        // removes an ordering bug that was invisible by construction: a decode path that ran before
        // any vault operation threw, was swallowed, and simply produced no image — hidden media
        // silently unreadable to the indexer while looking fine everywhere else.
        ai.rightone.finderplus.media.VaultCrypto.init(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

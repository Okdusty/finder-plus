package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.IndexProgress

/**
 * Lets the UI layer react to indexing progress without engine-index depending on :app.
 * The app binds an implementation that refreshes the Glance widget; the worker drives the
 * notification itself. A no-op default keeps the engine usable headless (tests, cron).
 */
interface IndexStatusListener {
    suspend fun onProgress(progress: IndexProgress)

    object NoOp : IndexStatusListener {
        override suspend fun onProgress(progress: IndexProgress) = Unit
    }
}

package ai.dusty.finderplus.index

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.vector.BruteForceVectorStore
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.text.TextEmbedder
import ai.dusty.finderplus.text.TextRole
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Checks the on-device text embedder without touching the index.
 *
 * `text` is embedded as a document and its first components logged, for comparison with a host
 * reference run on the same string. Each of `queries` ('|'-separated) is embedded as a query and
 * ranked against the text vectors already in the index.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.PROBE_TEXT -p <pkg> [--es text "..."] [--es queries "a|b"]
 */
@HiltWorker
class TextEmbedProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val embedder: TextEmbedder,
    private val db: FinderDatabase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        log("ready=${embedder.isReady()} model=${embedder.modelId} dim=${embedder.dimension()}")
        if (!embedder.isReady()) return Result.success()
        val text = inputData.getString(KEY_TEXT)
        val queries = inputData.getString(KEY_QUERIES)?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (text == null && queries.isEmpty()) { log("nothing to do: pass --es text and/or --es queries"); return Result.success() }

        if (text != null) {
            val t0 = System.currentTimeMillis()
            val v = embedder.embed(text, TextRole.DOCUMENT)
            log("document embed ${System.currentTimeMillis() - t0} ms · v[0..8] = " + v.take(8).joinToString(" ") { "%.5f".format(it) })
        }

        val store = BruteForceVectorStore(db.embeddingDao())
        log("index holds ${db.embeddingDao().countOfKind(EmbeddingKind.TEXT.ordinal)} text vectors")
        for (q in queries) {
            val t1 = System.currentTimeMillis()
            val v = embedder.embed(q, TextRole.QUERY)
            val ms = System.currentTimeMillis() - t1
            val shown = store.search(EmbeddingKind.TEXT, v, k = 5).map { h ->
                "%.3f %s".format(h.score, db.mediaItemDao().byId(h.itemId)?.display_name ?: h.itemId)
            }.joinToString(" | ")
            log("'$q' (${ms} ms) → $shown")
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i("finderEmbed", msg)

    companion object {
        private const val KEY_TEXT = "text"
        private const val KEY_QUERIES = "queries"

        fun enqueue(context: Context, text: String?, queries: String?) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-probe-text", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TextEmbedProbeWorker>()
                    .setInputData(workDataOf(KEY_TEXT to text, KEY_QUERIES to queries))
                    .build(),
            )
        }
    }
}

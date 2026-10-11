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
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.vision.ImageAnalyzer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * What the concept head says about chosen items, computed fresh from their pixels: embeds each photo
 * the way indexing does and runs [ConceptClassifier] on the result, with timings. Nothing is written to
 * the index, so it works on items indexing has not reached. Writes `files/concept-probe.txt`.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.PROBE_CONCEPTS -p <pkg> --es idsFile <name>
 *
 * The ids file holds one item id per line, in the app's files directory.
 */
@HiltWorker
class ConceptProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val analyzer: ImageAnalyzer,
    private val classifier: ConceptClassifier,
    private val coordinator: ModelCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val idsFile = inputData.getString(KEY_IDS_FILE)?.let { File(applicationContext.filesDir, it) }
        val out = File(applicationContext.filesDir, "concept-probe.txt")
        if (idsFile == null || !idsFile.isFile) { out.writeText("# no ids file\n"); return Result.success() }
        val ids = idsFile.readLines().mapNotNull { it.trim().toLongOrNull() }
        val images = DecodedImageCache(applicationContext)

        out.bufferedWriter().use { w ->
            for (id in ids) {
                if (isStopped) break
                val row = db.mediaItemDao().byId(id) ?: continue
                val bmp = images.get(row.content_uri, DECODE_EDGE) ?: continue
                val t0 = System.currentTimeMillis()
                val vec = coordinator.withModel(RequiredModel.CLIP_IMG) { analyzer.embed(bmp) }
                val embedMs = System.currentTimeMillis() - t0
                if (vec.all { it == 0f }) { w.write("ITEM $id: no CLIP vector (model missing?)\n"); continue }
                val t1 = System.currentTimeMillis()
                val reading = classifier.read(listOf(Vecs.normalized(vec)), personal = emptyList(), limit = LIMIT)
                val readMs = System.currentTimeMillis() - t1
                val concepts = reading.concepts.joinToString(", ") { "${it.label} %.2f".format(it.score) }
                w.write("ITEM $id embed=${embedMs}ms read=${readMs}ms domain=${reading.domain} %.2f | $concepts\n".format(reading.domainConfidence))
                w.flush()
            }
        }
        android.util.Log.i("finderProbe", "wrote ${out.absolutePath}")
        return Result.success()
    }

    companion object {
        private const val KEY_IDS_FILE = "idsFile"
        private const val DECODE_EDGE = 1024
        private const val LIMIT = 8

        fun enqueue(context: Context, idsFile: String?) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-probe-concepts", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ConceptProbeWorker>().setInputData(workDataOf(KEY_IDS_FILE to idsFile)).build(),
            )
        }
    }
}

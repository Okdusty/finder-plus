package ai.dusty.finderplus.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.media.Bitmaps
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.model.TagSource
import ai.dusty.finderplus.vision.ClipTextEncoder
import ai.dusty.finderplus.vision.DetectionFilter
import ai.dusty.finderplus.vision.ImageAnalyzer
import ai.dusty.finderplus.vision.ImageDescriber
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Explains object labels: re-runs the describe model on chosen items and writes what it said — the
 * caption, and per object its confidence, box and share of the image, and the doubts the review would
 * raise — next to the tags stored for the item. Output is `files/label-probe.txt`, the input of
 * tools/label-audit; nothing in the index changes.
 *
 * Every item with an object label is probed unless `labels` narrows it (comma-separated) or `idsFile`
 * names a file of item ids in the app's files dir, which works on items indexing has not reached.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.PROBE_LABELS -p <pkg> [--es labels <a,b>] [--ei limit <n>] [--es idsFile <name>]
 */
@HiltWorker
class LabelProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val describer: ImageDescriber,
    private val analyzer: ImageAnalyzer,
    private val clipText: ClipTextEncoder,
    private val coordinator: ModelCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Explaining labels")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        // Describing takes minutes per photo on a phone; without foreground status the system stops a
        // background worker long before it finishes. Refused when started from the background (Android 12+).
        val foreground = runCatching { setForeground(getForegroundInfo()) }.isSuccess
        val out = File(applicationContext.filesDir, "label-probe.txt")
        if (!describer.isReady()) { out.writeText("# describe model not installed\n"); return Result.success() }
        val labels = inputData.getString(KEY_LABELS).orEmpty().split(',').map { it.trim() }
            .filter { it.isNotEmpty() && it != "*" }
        val limit = inputData.getInt(KEY_LIMIT, Int.MAX_VALUE)

        // An ids file wins because after a reset the new index has not labelled those items yet.
        val idsFile = inputData.getString(KEY_IDS_FILE)?.let { File(applicationContext.filesDir, it) }
        val ids = (when {
            idsFile != null && idsFile.isFile -> idsFile.readLines().mapNotNull { it.trim().toLongOrNull() }
            labels.isEmpty() -> db.contentDao().itemsWithSource(TagSource.OBJECT.ordinal)
            else -> labels.flatMap { l -> db.contentDao().itemsWithTag(TagSource.OBJECT.ordinal, l) }
        }).distinct().take(limit)
        val scope = labels.ifEmpty { listOf("all labels") }.joinToString()
        val review = DetectionReview(db, analyzer, clipText, coordinator)
        log("probing ${ids.size} items for $scope with ${describer.modelId}")

        out.bufferedWriter().use { w ->
            w.write("# label probe: $scope · model ${describer.modelId} · foreground=$foreground\n")
            for (id in ids) {
                if (isStopped) break
                val row = db.mediaItemDao().byId(id) ?: continue
                val video = row.kind == 1
                val frame = if (video) MediaThumbs.describedFrame(applicationContext, db.embeddingDao(), id, row.content_uri, DECODE_EDGE) else null
                val bmp = frame?.bitmap ?: (if (video) null else Bitmaps.decodeDownscaled(applicationContext, row.content_uri, DECODE_EDGE)) ?: continue
                val exif = if (video) 0 else Bitmaps.exifDegrees(applicationContext, row.content_uri)
                w.write("\nITEM $id \"${row.display_name}\" folder=${row.bucket_name} ${if (video) "video-frame@${frame?.atMs ?: "thumbnail"}" else "photo"} " +
                    "stored=${row.width}x${row.height} decoded=${bmp.width}x${bmp.height} exif=$exif°\n")
                val stored = db.contentDao().tagsForItem(id)
                    .joinToString(" | ") { "${TagSource.entries.getOrNull(it.source)?.name ?: it.source}:${it.label}:${"%.2f".format(it.confidence)}" }
                w.write("  stored tags: $stored\n")

                val t0 = System.currentTimeMillis()
                val d = coordinator.withModel(RequiredModel.VLM) { describer.describe(bmp) }
                val t1 = System.currentTimeMillis()
                val regions = DetectionFilter.classAgnosticNms(DetectionFilter.groundedOnly(d.objects, d.grounded))
                val outcome = review.review(id, bmp, regions)
                val t2 = System.currentTimeMillis()
                w.write("  describe ${t1 - t0}ms · review ${t2 - t1}ms\n")
                w.write("  caption: ${d.caption}\n")
                w.write("  [as indexed]\n")
                val asked = outcome.questions.associateBy { it.label }
                val area = (bmp.width.toFloat() * bmp.height).coerceAtLeast(1f)
                for (r in regions.sortedByDescending { it.confidence }) {
                    val q = asked[r.label]
                    val doubts = q?.let { Doubt.of(it.reasons).joinToString(",") + (it.alternative?.let { a -> "→$a" } ?: "") }.orEmpty()
                    w.write(
                        "    LABEL %-14s conf %.2f · box [%.2f,%.2f,%.2f,%.2f] · %.1f%% of image · doubts: %s\n".format(
                            r.label, r.confidence,
                            r.left / bmp.width.toFloat(), r.top / bmp.height.toFloat(),
                            r.right / bmp.width.toFloat(), r.bottom / bmp.height.toFloat(),
                            (r.right - r.left) * (r.bottom - r.top) / area * 100, doubts,
                        )
                    )
                }
                if (video) bmp.recycle()
                w.flush()
            }
        }
        log("wrote ${out.absolutePath}")
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i("finderProbe", msg)

    companion object {
        private const val KEY_LABELS = "labels"
        private const val KEY_LIMIT = "limit"
        private const val KEY_IDS_FILE = "idsFile"
        private const val DECODE_EDGE = 1024
        private const val NOTIF_ID = 4212

        fun enqueue(context: Context, labels: String?, limit: Int = Int.MAX_VALUE, idsFile: String? = null) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-probe-labels", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LabelProbeWorker>()
                    .setInputData(workDataOf(KEY_LABELS to labels, KEY_LIMIT to limit, KEY_IDS_FILE to idsFile))
                    .build(),
            )
        }
    }
}

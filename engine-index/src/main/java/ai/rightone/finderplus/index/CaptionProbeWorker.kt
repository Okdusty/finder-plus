package ai.rightone.finderplus.index

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
import ai.rightone.finderplus.db.FinderDatabase
import ai.rightone.finderplus.model.RequiredModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Captions a handful of real items NOW and logs text + latency, so the VLM path is verified in minutes
 * instead of after the hour of queue ahead of the CAPTION tier. The caption pass's failure mode is a
 * silent empty string, which is exactly the kind of thing that must not be discovered at hour three.
 *
 *   adb shell am broadcast -a ai.rightone.finderplus.action.PROBE_CAPTION -p <pkg>
 */
@HiltWorker
class CaptionProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val captioner: ai.rightone.finderplus.speech.VlmCaptioner,
    private val coordinator: ModelCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Testing scene descriptions")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(4208, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        log("ready=${captioner.isReady()}")
        if (!captioner.isReady()) return Result.success()

        val cache = DecodedImageCache(applicationContext)
        val items = db.mediaItemDao().itemsWithEmbeddingNoUserLabel(SAMPLES)
        for (id in items) {
            val row = db.mediaItemDao().byId(id) ?: continue
            val video = row.kind == 1
            val bmp = (if (video) MediaThumbs.load(applicationContext, row.content_uri, 1024)
                       else cache.get(row.content_uri, 1024)) ?: continue
            val t0 = System.currentTimeMillis()
            val text = coordinator.withModel(RequiredModel.VLM) { captioner.caption(bmp) }
            val ms = System.currentTimeMillis() - t0
            if (video) bmp.recycle()
            log("${ms}ms ${if (video) "video" else "image"} ${row.display_name}: \"$text\"")
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i("finderCaptionProbe", msg)

    companion object {
        private const val SAMPLES = 6

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-caption-probe", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CaptionProbeWorker>().build(),
            )
        }
    }
}

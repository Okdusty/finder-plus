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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Groups similar media once indexing finishes, so the user can name a whole group in one answer.
 *
 * Enqueued by [IndexWorker] when the work queue empties — not during indexing. Clusters built from a
 * partial index fragment, because the items that belong to a group may simply not be embedded yet, and
 * the user would be asked to name the same concept again and again as they arrive.
 *
 *   adb shell am broadcast -a ai.rightone.finderplus.action.CLUSTER -p <pkg>
 */
@HiltWorker
class ClusterWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val clusterer: SimilarityClusterer,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Grouping similar media")
            .setContentText("Finding things worth naming…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val started = System.currentTimeMillis()
        val clusters = clusterer.cluster(isStopRequested = { isStopped })
        val secs = (System.currentTimeMillis() - started) / 1000

        val faceGroups = runCatching { clusterer.clusterFaces() }.getOrDefault(emptyList())
        val embedded = runCatching { db.faceDao().facesEmbeddedCount() }.getOrDefault(0)
        val pending = runCatching { db.contentDao().pendingSuggestionCount() }.getOrDefault(0)
        log("found ${clusters.size} scene groups + ${faceGroups.size} people groups " +
            "(from $embedded embedded faces) in ${secs}s; $pending suggestions awaiting review")
        for (f in faceGroups.take(LOG_TOP)) {
            log("people group of %d (cohesion %.3f) rep=%d".format(f.members.size, f.cohesion, f.representative))
        }

        for (c in clusters.take(LOG_TOP)) {
            log(
                "group of %d (cohesion %.3f) rep=%d  already: %s".format(
                    c.members.size, c.cohesion, c.representative,
                    c.existingLabels.joinToString(", ").ifEmpty { "(nothing)" },
                )
            )
        }
        if (clusters.isEmpty()) {
            log("no groups met the size/similarity floor — nothing worth asking about")
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-cluster"
        private const val TAG = "finderCluster"
        private const val NOTIF_ID = 4207
        private const val LOG_TOP = 15

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                // KEEP, not REPLACE: several slices can finish in a row reporting an empty queue, and
                // restarting an O(n^2) pass each time would starve it of ever completing.
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ClusterWorker>().build(),
            )
        }
    }
}

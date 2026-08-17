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
import androidx.work.workDataOf
import ai.rightone.finderplus.model.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Wipes the derived index and rebuilds it.
 *
 * Sequenced deliberately: wipe, then run one short slice so the scan restores `media_item` rows, then
 * put the user's labels back, and only then hand off to the normal indexing chain. Restoring labels
 * before the scan would fail on the foreign key; restoring them after the full index would leave the
 * early passes working from incomplete evidence.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.RESET_DB -p <pkg>
 *   adb shell am broadcast -a ...action.RESET_DB -p <pkg> --ez keepLabels false
 */
@HiltWorker
class ResetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reset: IndexReset,
    private val orchestrator: IndexOrchestrator,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Rebuilding index")
            .setContentText("Clearing previous results…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val keepLabels = inputData.getBoolean(KEY_KEEP_LABELS, true)

        val report = reset.wipe(keepUserLabels = keepLabels)
        log("wiped ${report.itemsCleared} items; preserved ${report.userLabelsPreserved} user labels")

        // A short slice: enough to make the scan repopulate media_item, not enough to start burning
        // battery on passes before the labels are back.
        runCatching { orchestrator.runSlice(Trigger.MANUAL, budgetMs = SCAN_BUDGET_MS) { } }
            .onFailure { log("scan slice failed: ${it.javaClass.simpleName}: ${it.message}") }

        val restored = reset.restoreUserLabels()
        log("restored $restored user labels")

        IndexWorker.enqueue(applicationContext, Trigger.MANUAL)
        log("reset complete — full index enqueued")
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-reset"
        const val KEY_KEEP_LABELS = "keepLabels"
        private const val TAG = "finderReset"
        private const val NOTIF_ID = 4205
        private const val SCAN_BUDGET_MS = 60_000L

        fun enqueue(context: Context, keepLabels: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ResetWorker>()
                    .setInputData(workDataOf(KEY_KEEP_LABELS to keepLabels))
                    .build(),
            )
        }
    }
}

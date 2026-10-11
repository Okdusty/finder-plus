package ai.dusty.finderplus.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Process
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.model.IndexProgress
import ai.dusty.finderplus.model.RunStatus
import ai.dusty.finderplus.model.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs one bounded indexing slice as a data-sync foreground service, shows progress in the
 * notification, then schedules the next slice after a thermal- and battery-aware cool-down.
 *
 * Slicing is required because WorkManager stops any worker after about 10 minutes, so a single loop
 * could never finish a full index. See docs/design/07-BATTERY-POLICY.md.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: IndexOrchestrator,
    private val power: PowerPolicy,
    private val db: ai.dusty.finderplus.db.FinderDatabase,
    private val assistPrefs: AssistPrefs,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        return try {
            // Android 12+ may refuse a foreground service to work started in the background (after a
            // reset, a model install, a scheduled run). A slice fits inside WorkManager's 10-minute
            // window anyway, so run without the notification rather than retry into the same refusal.
            runCatching { setForeground(foregroundInfo(null)) }
                .onFailure { android.util.Log.i(TAG, "running without a foreground notification: ${it.javaClass.simpleName}") }

            // A reset wipes label_prototype, and without a seed vocabulary every CONCEPTS unit parks as
            // SKIPPED with no error. This worker always runs after a reset, so it owns the check.
            runCatching {
                if (db.labelPrototypeDao().countByOrigin(
                        ai.dusty.finderplus.db.entity.LabelPrototypeEntity.ORIGIN_SEED) == 0) {
                    android.util.Log.i(TAG, "seed vocabulary empty — enqueueing VocabularySeedWorker")
                    VocabularySeedWorker.enqueue(applicationContext)
                }
            }
            val trigger = Trigger.entries[
                inputData.getInt(KEY_TRIGGER, Trigger.MANUAL.ordinal).coerceIn(0, Trigger.entries.lastIndex)
            ]

            var lastShown = 0L
            // A foreground service runs at an elevated nice value, so indexing would compete with the
            // UI for CPU. Pin the slice to one background-priority thread instead: same throughput,
            // far less scheduling pressure and heat.
            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    r.run()
                }, "finder-index")
            }.asCoroutineDispatcher()

            val outcome = try {
                withContext(dispatcher) {
                    orchestrator.runSlice(trigger) { progress ->
                        // Throttle notification updates: the system rate-limits rapid posts anyway.
                        val now = System.currentTimeMillis()
                        if (now - lastShown >= NOTIFY_INTERVAL_MS || progress.status != RunStatus.RUNNING) {
                            lastShown = now
                            runCatching { setForeground(foregroundInfo(progress)) }
                        }
                    }
                }
            } finally {
                dispatcher.close()
            }

            if (outcome.workRemaining && !outcome.stopped && !isStopped) {
                scheduleNextSlice(applicationContext, power.coolDownMs(outcome.yieldReason))
            } else if (!outcome.workRemaining && !outcome.stopped) {
                // Cluster only once every embedding exists. Built from a partial index, clusters
                // fragment and the user ends up naming the same concept repeatedly.
                ClusterWorker.enqueue(applicationContext)
            }

            // Summon the judge only when the suggestion queue has grown past the UNSURE residue the
            // last run left. Comparing with that watermark, not zero, stops a judge-index-judge loop
            // over a queue of hedges that never shrinks.
            if (assistPrefs.mode != AssistPrefs.Mode.MANUAL &&
                db.contentDao().pendingSuggestionCount() > assistPrefs.lastQueueSize
            ) {
                JudgeWorker.enqueue(applicationContext)
            }
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "index slice failed", t)
            // The ledger is intact; WorkManager retries with backoff and the engine resumes.
            Result.retry()
        }
    }

    private fun foregroundInfo(progress: IndexProgress?): ForegroundInfo {
        val ctx = applicationContext
        ensureChannel(ctx)

        val total = progress?.total ?: 0
        val done = progress?.done ?: 0
        val pct = if (total > 0) (done * 100 / total) else 0
        val indeterminate = total == 0 || progress?.status == RunStatus.SCANNING

        val title = when (progress?.status) {
            RunStatus.SCANNING -> "Scanning your gallery…"
            RunStatus.PAUSED -> "Indexing paused"
            RunStatus.STOPPED -> "Indexing stopped"
            RunStatus.DONE -> "Gallery indexed"
            else -> "Indexing your gallery"
        }
        val detail = buildString {
            if (indeterminate) {
                append("Finding photos, videos and audio…")
            } else {
                append("%,d / %,d · %d%%".format(done, total, pct))
                progress?.currentPass?.let { append(" · ").append(it.uiLabel()) }
            }
            if (progress?.status == RunStatus.PAUSED) append(" · waiting to cool down")
        }

        val stopIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(IndexControlReceiver.ACTION_STOP).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (indeterminate) 0 else total, if (indeterminate) 0 else done, indeterminate)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build()
            )
            .build()

        return ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE_WORK = "finder-index"
        const val CHANNEL_ID = "finder.indexing"
        const val KEY_TRIGGER = "trigger"
        private const val NOTIF_ID = 4201
        private const val TAG = "finderIndex"
        private const val NOTIFY_INTERVAL_MS = 1_500L

        /**
         * Every worker that posts a foreground notification uses this channel. It must exist before
         * the first one does — on a fresh install a vault or probe worker could run before any index,
         * and Android kills the process for a notification on a missing channel.
         */
        fun ensureChannel(ctx: Context) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Indexing", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress while finder+ indexes your gallery"
                        setShowBadge(false)
                    }
                )
            }
        }

        private fun request(trigger: Trigger, delayMs: Long, automatic: Boolean = false) =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to trigger.ordinal))
                .setConstraints(
                    // Never start a burn on a nearly-dead battery; the policy handles the rest.
                    Constraints.Builder().setRequiresBatteryNotLow(true)
                        .setRequiresCharging(
                            ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.ProcessingSettings.ONLY_CHARGING]
                        )
                        .setRequiresDeviceIdle(
                            automatic && ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.ScheduleSettings.IDLE_ONLY]
                        )
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
                .build()

        /**
         * Enqueue an incremental index. Replaces any pending slice chain: a continuation is usually
         * queued, so KEEP would turn "Update" into a no-op and a rescan could never be forced. Safe
         * because a cancelled slice's unit is reclaimed by lease expiry with its checkpoint intact.
         */
        fun enqueue(context: Context, trigger: Trigger = Trigger.MANUAL) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(trigger, 0))
        }

        /** An automatic run: it rescans like a manual one, but leaves a run already under way alone. */
        fun enqueueAutomatic(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(Trigger.SCHEDULED, 0, automatic = true))
        }

        /**
         * Queue the next slice after [delayMs] of cool-down. APPEND_OR_REPLACE chains it behind the
         * finishing slice under the same unique name, so slices never overlap and "Index now" stays a
         * no-op while a chain is pending.
         */
        internal fun scheduleNextSlice(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(Trigger.CONTINUATION, delayMs),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}

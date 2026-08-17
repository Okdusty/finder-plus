package ai.rightone.finderplus.index

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
import ai.rightone.finderplus.model.IndexProgress
import ai.rightone.finderplus.model.RunStatus
import ai.rightone.finderplus.model.Trigger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs ONE bounded indexing slice as a data-sync foreground service, publishes live status to the
 * notification, then self-schedules the next slice after a thermal/battery-aware cool-down.
 *
 * Slicing is what makes a full-gallery index survivable: WorkManager kills any worker that runs past
 * ~10 minutes, so a single long loop could never finish (and pinned the CPU while it lasted).
 * See docs/design/07-BATTERY-POLICY.md.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: IndexOrchestrator,
    private val power: PowerPolicy,
    private val db: ai.rightone.finderplus.db.FinderDatabase,
    private val assistPrefs: AssistPrefs,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result {
        return try {
            setForeground(foregroundInfo(null))

            // Self-healing for the vocabulary. RESET_DB wipes label_prototype, and seeding used to be
            // a manual debug broadcast — forget it once and every CONCEPTS unit parks as SKIPPED,
            // leaving the whole gallery unlabelled with no error (measured: 4,857 items). The index
            // worker is the one thing guaranteed to run after any reset, so it owns the check.
            runCatching {
                if (db.labelPrototypeDao().countByOrigin(
                        ai.rightone.finderplus.db.entity.LabelPrototypeEntity.ORIGIN_SEED) == 0) {
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
                // The queue is empty, so every embedding exists — the only point at which clusters are
                // trustworthy. Built from a partial index they fragment, and the user ends up naming the
                // same concept repeatedly as the missing members trickle in.
                ClusterWorker.enqueue(applicationContext)
            }

            // Indexing has minted new questions and the user delegated answering: summon the judge —
            // but only when the queue has *grown* past the UNSURE residue the last run left behind.
            // Comparing against that watermark (not zero) is what breaks the judge→index→judge
            // ping-pong that a static queue of hedges would otherwise sustain forever.
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

        private fun ensureChannel(ctx: Context) {
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

        private fun request(trigger: Trigger, delayMs: Long) =
            OneTimeWorkRequestBuilder<IndexWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to trigger.ordinal))
                .setConstraints(
                    // Never start a burn on a nearly-dead battery; the policy handles the rest.
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }
                .build()

        /**
         * Enqueue an incremental index.
         *
         * An explicit request REPLACEs any pending slice chain rather than being dropped: with KEEP, a
         * continuation was always queued, so "Update" became a silent no-op and a rescan (needed to pick
         * up newly added passes) could never be forced. Preempting is safe because the engine is
         * resumable — a cancelled slice's unit is reclaimed by lease expiry with its checkpoint intact.
         */
        fun enqueue(context: Context, trigger: Trigger = Trigger.MANUAL) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(trigger, 0))
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

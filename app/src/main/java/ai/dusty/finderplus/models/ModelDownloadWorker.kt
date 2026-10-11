package ai.dusty.finderplus.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.index.IndexWorker
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.speech.ModelDownloadException
import ai.dusty.finderplus.speech.ModelManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Drains [ModelInstaller]'s queue: one top-level model at a time, each with its companions, each file
 * resumable and checksum-verified by [ModelManager.download].
 *
 * A network drop is retried with backoff (the bytes already fetched are kept); a definite failure —
 * no space, a bad checksum, a missing file upstream — is recorded against that model and the queue
 * moves on, so one broken download never blocks the rest.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val models: ModelManager,
    private val installer: ModelInstaller,
    private val db: FinderDatabase,
) : CoroutineWorker(appContext, params) {

    private var lastNotify = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing download…", 0, 0)

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        var installedAny = false

        while (true) {
            val id = installer.head() ?: break
            val spec = ModelCatalog.byId(id)
            if (spec == null) { installer.failed(id, "Unknown model"); continue }

            val files = ModelCatalog.withCompanions(spec).filterNot { models.isInstalled(it.id) }
            val bundleTotal = files.sumOf { it.sizeBytes }
            var before = 0L
            try {
                for (f in files) {
                    models.download(f.id).collect { p ->
                        val received = before + p.received.coerceAtMost(f.sizeBytes)
                        setProgress(
                            workDataOf(
                                KEY_CURRENT to id, KEY_FILE to f.label,
                                KEY_RECEIVED to received, KEY_TOTAL to bundleTotal,
                                KEY_VERIFYING to p.verifying,
                            )
                        )
                        notifyProgress(spec.label, received, bundleTotal, p.verifying)
                    }
                    before += f.sizeBytes
                }
                installer.completed(id)
                installedAny = true
                Log.i(TAG, "installed ${spec.id} (${files.size} files)")
            } catch (c: CancellationException) {
                throw c
            } catch (e: ModelDownloadException) {
                Log.w(TAG, "install ${spec.id} failed: ${e.message}")
                if (e.retryable && runAttemptCount < MAX_RETRIES) {
                    // The network will come back: keep the queue and the bytes, try again with backoff.
                    if (installedAny) wake()
                    return Result.retry()
                }
                installer.failed(id, e.message ?: "Download failed")
            } catch (e: IOException) {
                // A dropped connection mid-file: keep the queue and the partial bytes, try again soon.
                Log.w(TAG, "install ${spec.id} interrupted: $e")
                if (runAttemptCount < MAX_RETRIES) {
                    if (installedAny) wake()
                    return Result.retry()
                }
                installer.failed(id, "Connection kept dropping — tap Install to resume")
            }
        }

        if (installedAny) wake()
        return Result.success()
    }

    /** New models change what the index can do: revive the units that parked waiting for them. */
    private suspend fun wake() {
        installer.notifyInstalledChanged()
        val revived = runCatching { db.workUnitDao().requeueAllSkipped(System.currentTimeMillis()) }.getOrDefault(0)
        val anyIndexed = runCatching { db.mediaItemDao().count() > 0 }.getOrDefault(false)
        if (revived > 0 || anyIndexed) IndexWorker.enqueue(applicationContext)
        Log.i(TAG, "revived $revived parked units")
    }

    private suspend fun notifyProgress(label: String, received: Long, total: Long, verifying: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1_000) return
        lastNotify = now
        val text = if (verifying) "Verifying $label…" else "$label · ${mb(received)} of ${mb(total)}"
        runCatching { setForeground(foregroundInfo(text, received, total)) }
    }

    private fun foregroundInfo(text: String, received: Long, total: Long): ForegroundInfo {
        ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, ai.dusty.finderplus.ui.settings.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Installing models")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (total > 0) setProgress(1000, (received * 1000 / total).toInt(), false)
                else setProgress(0, 0, true)
            }
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE = "finder-model-downloads"
        const val KEY_CURRENT = "current"
        const val KEY_FILE = "file"
        const val KEY_RECEIVED = "received"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        private const val CHANNEL_ID = "finder.models"
        private const val NOTIF_ID = 4231
        private const val MAX_RETRIES = 5
        private const val TAG = "finderModels"

        /**
         * KEEP: a running worker already loops until the queue is empty, so a second install request
         * only needs to land in the queue. After a cancel the old run is being torn down, so the
         * follow-up must REPLACE it rather than be dropped as a duplicate.
         */
        fun enqueue(context: Context, afterCancel: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                if (afterCancel) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        private fun ensureChannel(ctx: Context) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress while finder+ downloads on-device AI models"
                        setShowBadge(false)
                    }
                )
            }
        }

        private fun mb(bytes: Long) = "${bytes / 1_000_000} MB"
    }
}

package ai.dusty.finderplus.index

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.settings.ProcessingSettings
import ai.dusty.finderplus.settings.ScheduleSettings
import ai.dusty.finderplus.settings.Settings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Indexing that starts on its own, as the schedule settings say: on a timer, when new media appears,
 * and when a charger is connected. New media can also sweep folders set to hide automatically without
 * a full index. [apply] arms or cancels each trigger; it runs when the app starts and whenever the
 * schedule changes.
 */
object AutoIndex {

    private const val TIMER = "finder-auto-timer"
    private const val NEW_MEDIA = "finder-auto-media"
    private const val CHARGER = "finder-auto-charger"
    internal const val KEY_REASON = "reason"

    enum class Reason { TIMER, NEW_MEDIA, CHARGER }

    fun apply(context: Context) {
        val wm = WorkManager.getInstance(context)
        val hours = Settings.valueOrNull(ScheduleSettings.EVERY_HOURS)
        if (hours != null) {
            wm.enqueueUniquePeriodicWork(
                TIMER, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutoIndexWorker>(hours.toLong().coerceAtLeast(1), TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .setInputData(workDataOf(KEY_REASON to Reason.TIMER.name))
                    .build(),
            )
        } else wm.cancelUniqueWork(TIMER)

        if (Settings[ScheduleSettings.ON_NEW_MEDIA] || Settings[ScheduleSettings.SWEEP_NEW]) {
            armNewMedia(context, ExistingWorkPolicy.KEEP)
        } else wm.cancelUniqueWork(NEW_MEDIA)

        if (Settings[ScheduleSettings.ON_CHARGER]) armCharger(context, delayMs = 0, ExistingWorkPolicy.KEEP)
        else wm.cancelUniqueWork(CHARGER)
    }

    /** What an automatic run waits for before it starts. */
    internal fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(Settings[ProcessingSettings.ONLY_CHARGING])
        .setRequiresDeviceIdle(Settings[ScheduleSettings.IDLE_ONLY])
        .build()

    /**
     * Fires once after media changes. The delays gather a burst (a camera roll syncing, a download of
     * many files) into one run; it is armed again after each firing.
     */
    internal fun armNewMedia(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NEW_MEDIA, policy,
            OneTimeWorkRequestBuilder<AutoIndexWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_REASON to Reason.NEW_MEDIA.name))
                .build(),
        )
    }

    /**
     * Fires when a charger is connected. Android offers apps no "charger connected" event, so this is
     * work that needs charging: it runs at the connection, and is armed again an hour later, which
     * also covers a phone that stays on the charger.
     */
    internal fun armCharger(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CHARGER, policy,
            OneTimeWorkRequestBuilder<AutoIndexWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(true)
                        .setRequiresDeviceIdle(Settings[ScheduleSettings.IDLE_ONLY]).build()
                )
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_REASON to Reason.CHARGER.name))
                .build(),
        )
    }
}

@HiltWorker
class AutoIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val vaultPolicy: VaultPolicy,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reason = runCatching { AutoIndex.Reason.valueOf(inputData.getString(AutoIndex.KEY_REASON) ?: "") }.getOrNull()
            ?: return Result.success()
        val ctx = applicationContext
        android.util.Log.i("finderAuto", "automatic trigger: $reason")
        when (reason) {
            AutoIndex.Reason.TIMER -> IndexWorker.enqueueAutomatic(ctx)
            AutoIndex.Reason.NEW_MEDIA -> {
                if (Settings[ScheduleSettings.ON_NEW_MEDIA]) IndexWorker.enqueueAutomatic(ctx)
                else if (Settings[ScheduleSettings.SWEEP_NEW] && vaultPolicy.auto && vaultPolicy.hasHideRules() &&
                    ai.dusty.finderplus.media.VaultCrypto.isRecoveryKeySaved(ctx)
                ) VaultWorker.enqueue(ctx)
                // A content trigger fires once; arm it for the next change.
                if (Settings[ScheduleSettings.ON_NEW_MEDIA] || Settings[ScheduleSettings.SWEEP_NEW]) {
                    AutoIndex.armNewMedia(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
                }
            }
            AutoIndex.Reason.CHARGER -> {
                IndexWorker.enqueueAutomatic(ctx)
                if (Settings[ScheduleSettings.ON_CHARGER]) {
                    AutoIndex.armCharger(ctx, CHARGER_RECHECK_MS, ExistingWorkPolicy.APPEND_OR_REPLACE)
                }
            }
        }
        return Result.success()
    }

    private companion object {
        const val CHARGER_RECHECK_MS = 60 * 60_000L
    }
}

/**
 * After a restart, WorkManager resumes an index that was running. With [ScheduleSettings.ON_BOOT] off
 * this cancels that; the index stays resumable from the widget.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings[ScheduleSettings.ON_BOOT]) IndexWorker.cancel(context)
        AutoIndex.apply(context)
    }
}

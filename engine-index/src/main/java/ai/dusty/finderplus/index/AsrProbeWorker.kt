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
import ai.dusty.finderplus.db.toMediaItem
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.speech.SpeechRecognizer
import ai.dusty.finderplus.speech.TranscribeCursor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Transcribes one A/V file and logs the result, to judge speech quality and throughput without
 * waiting for the TRANSCRIBE pass in a full index. Runs as a worker because model load plus inference
 * far exceeds a BroadcastReceiver's ~10 s budget.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.TEST_ASR -p <pkg> --es kind audio
 */
@HiltWorker
class AsrProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val recognizer: SpeechRecognizer,
    private val booster: CpuBooster,
    private val coordinator: ModelCoordinator,
    private val pcm: ai.dusty.finderplus.media.PcmDecoder,
    private val models: ai.dusty.finderplus.speech.ModelManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Testing speech recognition")
            .setContentText("Transcribing one file…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) } // refused when started from the background (Android 12+); run without it
        val kind = if ((inputData.getString(KEY_KIND) ?: "audio").startsWith("v")) MediaKind.VIDEO
                   else MediaKind.AUDIO

        // ASR runs on the Vulkan GPU, so the cpuset should matter less than for the ONNX encoder.
        // This switch checks that: same file and windows, boosted or not, SoC temperature either side.
        val wantBoost = inputData.getBoolean(KEY_BOOST, false)

        // The injected recognizer always asks for the GPU, so a CPU-only comparison needs its own
        // instance.
        val useGpu = inputData.getBoolean(KEY_GPU, true)
        // ggml logs device capabilities and tensor placement only during load. Scoped to this probe:
        // the flag is global, and left on it floods logcat.
        ai.dusty.finderplus.speech.SpeechBackends.setVerboseLogging(true)
        val engine: SpeechRecognizer = if (useGpu) recognizer else
            ai.dusty.finderplus.speech.Qwen3SpeechRecognizer(pcm, configProvider = {
                models.asrConfig(ai.dusty.finderplus.model.Accelerator.CPU)
            })
        val applied = if (wantBoost) booster.boost() else false
        log("probe start: kind=$kind gpu=$useGpu ready=${engine.isReady()} " +
            "boost=$wantBoost applied=$applied cpuset=${booster.currentCpuset()} apStartC=${apTempC()}")
        if (!engine.isReady()) {
            log("NOT READY — model files or native library missing")
            return Result.success()
        }

        // A specific item can be named so two engines are compared on the same file.
        val wantId = inputData.getLong(KEY_ITEM, -1L)
        val row = if (wantId > 0) db.mediaItemDao().byId(wantId) else db.mediaItemDao().firstOfKind(kind.ordinal)
        if (row == null) {
            log("no $kind items in the index")
            return Result.success()
        }
        val item = row.toMediaItem()
        log("file=${item.displayName} duration=${item.durationMs}ms size=${item.sizeBytes}")

        val t0 = System.currentTimeMillis()
        var windows = 0
        var spoken = 0
        try {
            // Same residency lock as the TRANSCRIBE pass, so the probe never loads a second ASR model
            // beside a running index; that does not fail, it makes both crawl.
            coordinator.withModel(ai.dusty.finderplus.model.RequiredModel.ASR) {
            engine.transcribe(
                item = item,
                from = TranscribeCursor(0L, null),
                emit = { segments, next ->
                    windows++
                    // Re-assert per window, as the indexer does per unit; otherwise the platform demotes
                    // the process and a "boosted" run silently measures the little cores.
                    if (wantBoost) booster.ensureBoosted()
                    log("  window $windows cpuset=${booster.currentCpuset()}")
                    if (segments.isEmpty()) {
                        log("window $windows -> (no speech) next=${next.nextChunkStartMs}ms")
                    } else {
                        spoken++
                        for (s in segments) log("window $windows [${s.startMs}-${s.endMs}ms] \"${s.text}\"")
                    }
                },
                // Spot check, not a full transcription.
                isStopRequested = { windows >= MAX_WINDOWS },
            )
            }
        } catch (t: Throwable) {
            log("FAILED: ${t.javaClass.simpleName}: ${t.message}")
            return Result.success()
        } finally {
            // The flag is global; model load is done, so the capability output is already logged.
            ai.dusty.finderplus.speech.SpeechBackends.setVerboseLogging(false)
        }
        val ms = System.currentTimeMillis() - t0
        log(
            "done: $windows windows ($spoken with speech) in ${ms}ms " +
                "=> ${ms / windows.coerceAtLeast(1)}ms per 30s window " +
                "cpuset=${booster.currentCpuset()} apEndC=${apTempC()}"
        )
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    /** Application-processor temperature in Celsius, or -1 when the platform will not report it. */
    private fun apTempC(): String {
        val pm = applicationContext.getSystemService(android.os.PowerManager::class.java) ?: return "?"
        return runCatching {
            pm.getCurrentThermalStatus().let { status ->
                val zones = java.io.File("/sys/class/thermal").listFiles().orEmpty()
                val ap = zones.firstNotNullOfOrNull { z ->
                    val type = java.io.File(z, "type").takeIf { it.canRead() }?.readText()?.trim()
                    if (type == "AP" || type == "ap") java.io.File(z, "temp").takeIf { it.canRead() }?.readText()?.trim() else null
                }
                val c = ap?.toLongOrNull()?.let { it / 1000.0 }
                "${c ?: "?"}(status=$status)"
            }
        }.getOrDefault("?")
    }

    companion object {
        const val UNIQUE_WORK = "finder-asr-probe"
        const val KEY_KIND = "kind"
        const val KEY_BOOST = "boost"
        const val KEY_GPU = "gpu"
        const val KEY_ITEM = "item"
        private const val TAG = "finderAsrProbe"
        private const val NOTIF_ID = 4202
        private const val MAX_WINDOWS = 4

        fun enqueue(context: Context, kind: String, boost: Boolean = false, gpu: Boolean = true, itemId: Long = -1L) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AsrProbeWorker>()
                    .setInputData(workDataOf(KEY_KIND to kind, KEY_BOOST to boost, KEY_GPU to gpu, KEY_ITEM to itemId))
                    .build(),
            )
        }
    }
}

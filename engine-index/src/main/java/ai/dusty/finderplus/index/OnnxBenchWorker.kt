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
import ai.dusty.finderplus.vision.OnnxBench
import ai.dusty.finderplus.vision.OrtSessions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * Times ONNX models on this device with the indexer's own session settings, so a candidate model's cost
 * is measured where it would run rather than estimated from a desktop. See [OnnxBench] for the inputs.
 * Writes `files/onnx-bench.txt`.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.BENCH_ONNX -p <pkg> \
 *       --es models <path|path> [--es dims <name=n,name=n>] [--ei runs <n>]
 *
 * Paths are relative to the app's files directory.
 */
@HiltWorker
class OnnxBenchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Benchmarking a model")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        // Indexing runs as a foreground service, which decides the CPU cores it gets; measure under the same.
        val foreground = runCatching { setForeground(getForegroundInfo()) }.isSuccess
        val paths = inputData.getString(KEY_MODELS).orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val dims = inputData.getString(KEY_DIMS).orEmpty().split(',').mapNotNull { kv ->
            val (k, v) = kv.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            v.trim().toLongOrNull()?.let { k.trim() to it }
        }.toMap()
        val runs = inputData.getInt(KEY_RUNS, 5).coerceIn(1, 50)
        val out = File(applicationContext.filesDir, "onnx-bench.txt")
        out.bufferedWriter().use { w ->
            fun report(line: String) { w.write(line + "\n"); w.flush(); android.util.Log.i(TAG, line) }
            report("# threads=${OrtSessions.THREADS} foreground=$foreground runs=$runs dims=$dims")
            for (rel in paths) {
                if (isStopped) break
                report(OnnxBench.run(File(applicationContext.filesDir, rel), dims, runs))
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "finderBench"
        private const val NOTIF_ID = 4210
        private const val KEY_MODELS = "models"
        private const val KEY_DIMS = "dims"
        private const val KEY_RUNS = "runs"

        fun enqueue(context: Context, models: String?, dims: String?, runs: Int) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-onnx-bench", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OnnxBenchWorker>()
                    .setInputData(workDataOf(KEY_MODELS to models, KEY_DIMS to dims, KEY_RUNS to runs))
                    .build(),
            )
        }
    }
}

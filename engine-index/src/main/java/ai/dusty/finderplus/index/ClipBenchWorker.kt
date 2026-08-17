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
import ai.dusty.finderplus.speech.ModelManager
import ai.dusty.finderplus.vision.OnnxClipImageEncoder
import ai.dusty.finderplus.vision.OrtSessions
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Times the image encoder over a fixed set of real gallery images, writing nothing to the database.
 *
 * Exists because every acceleration attempt so far has needed measurement to settle, and two plausible
 * ones were wrong: XNNPACK ran at half a core, and int8 quantization was *slower* than fp32. A
 * repeatable benchmark that touches no state is the only way to A/B a change like a cpuset move without
 * re-running a six-hour index to find out.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.BENCH_CLIP -p <pkg>
 *   adb shell am broadcast -a ...action.BENCH_CLIP -p <pkg> --ez boost true --ei n 24
 */
@HiltWorker
class ClipBenchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val models: ModelManager,
    private val booster: CpuBooster,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Benchmarking visual encoder")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val count = inputData.getInt(KEY_N, 16).coerceIn(4, 64)
        val wantBoost = inputData.getBoolean(KEY_BOOST, false)
        val provider = if (inputData.getString(KEY_EP).equals("nnapi", ignoreCase = true)) {
            OrtSessions.Provider.NNAPI
        } else {
            OrtSessions.Provider.CPU
        }

        // A dedicated encoder rather than the injected analyzer: the shared one has already built a
        // CPU session, and reusing it would silently measure CPU while reporting the requested provider.
        val encoder = OnnxClipImageEncoder(
            modelPath = models.pathOf(ai.dusty.finderplus.model.ModelCatalog.CLIP_IMAGE.id) ?: "",
            modelId = ai.dusty.finderplus.model.ModelCatalog.CLIP_IMAGE.id,
            dim = 512,
            provider = provider,
        )

        val cpusetBefore = booster.currentCpuset()
        val boosted = if (wantBoost) booster.boost() else false
        log("cpuset=${booster.currentCpuset()} (was $cpusetBefore) boostRequested=$wantBoost applied=$boosted")

        val ids = db.mediaItemDao().itemsWithEmbeddingNoUserLabel(count * 2)
        val images = DecodedImageCache(applicationContext)
        val decodeMs = ArrayList<Long>(count)
        val embedMs = ArrayList<Long>(count)

        for (id in ids) {
            if (embedMs.size >= count || isStopped) break
            val item = db.mediaItemDao().byId(id)?.toMediaItem() ?: continue
            val t0 = System.currentTimeMillis()
            val bmp = images.get(item.uri, DECODE_EDGE) ?: continue
            val t1 = System.currentTimeMillis()
            val vec = encoder.encode(bmp)
            val t2 = System.currentTimeMillis()
            // A zero vector means the encoder silently failed; timing it would report a meaningless
            // "fast" result, which is exactly the sort of number that leads to a wrong decision.
            if (vec.all { it == 0f }) {
                log("ABORT: encoder returned a zero vector — $provider session not usable for this graph")
                return Result.success()
            }
            decodeMs += t1 - t0
            embedMs += t2 - t1
        }

        if (embedMs.isEmpty()) {
            log("no images available to benchmark")
            return Result.success()
        }
        // The first call includes session creation, which is one-time and would swamp a small sample.
        val warm = if (embedMs.size > 1) embedMs.drop(1) else embedMs
        log(
            "n=${embedMs.size} ep=$provider threads=${OrtSessions.THREADS} cpuset=${booster.currentCpuset()} " +
                "decode med=${median(decodeMs)}ms  embed first=${embedMs.first()}ms " +
                "med=${median(warm)}ms min=${warm.min()}ms max=${warm.max()}ms"
        )
        return Result.success()
    }

    private fun median(v: List<Long>): Long {
        if (v.isEmpty()) return 0
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-clip-bench"
        const val KEY_N = "n"
        const val KEY_BOOST = "boost"
        const val KEY_EP = "ep"
        private const val TAG = "finderClipBench"
        private const val NOTIF_ID = 4206
        private const val DECODE_EDGE = 1024

        fun enqueue(context: Context, n: Int, boost: Boolean, ep: String?) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ClipBenchWorker>()
                    .setInputData(workDataOf(KEY_N to n, KEY_BOOST to boost, KEY_EP to (ep ?: "cpu")))
                    .build(),
            )
        }
    }
}

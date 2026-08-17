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
 * Encodes [ZeroShotVocabulary] through the CLIP text tower once, turning each concept into a
 * prototype the gallery can be scored against.
 *
 * This is the one-time cost that buys recognition of everything the backbone already knows — after it
 * runs, labelling an item is a few hundred dot products against stored vectors, with no model
 * involved. It is a foreground worker because it takes minutes (the text tower measures ~1 s per
 * encode on this device) and must survive the app going to the background.
 *
 * Interrupting it is safe and loses nothing: a seeded label is skipped on the next run, so the
 * prototype table doubles as the checkpoint.
 *
 *   adb shell am broadcast -a ai.rightone.finderplus.action.SEED_VOCAB -p <pkg>
 */
@HiltWorker
class VocabularySeedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val learner: LabelLearner,
    private val classifier: ConceptClassifier,
    private val vocab: ConceptVocabulary,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, vocab.size)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Learning concepts")
            .setContentText("$done of $total")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val before = db.labelPrototypeDao().count()
        val started = System.currentTimeMillis()
        log("seeding ${vocab.size} concepts across ${vocab.domains.size} domains (table has $before)")

        val created = learner.seedVocabulary(
            labels = vocab.all,
            dim = CLIP_DIM,
            isStopRequested = { isStopped },
            onProgress = { done, total ->
                if (done % 10 == 0) {
                    setForeground(foregroundInfo(done, total))
                    log("  $done/$total")
                }
            },
        )

        val secs = (System.currentTimeMillis() - started) / 1000
        log("seeded $created new prototypes in ${secs}s; table now ${db.labelPrototypeDao().count()}")
        if (created == 0 && before == 0) {
            log("NOTHING SEEDED — the CLIP text tower or its vocabulary is not installed")
            return Result.success()
        }

        // Shape the vocabulary to this gallery. Concepts that never fire are not merely useless: they
        // keep taking softmax mass from the ones that do, so removing them raises confidence on real
        // matches. Runs here because it needs the seeded vocabulary to measure against.
        if (inputData.getBoolean(KEY_TUNE, true) && !isStopped) {
            val report = classifier.tuneToGallery(
                isStopRequested = { isStopped },
                onProgress = { done, total -> if (done % 50 == 0) log("  tuning $done/$total") },
            )
            log("tuned on ${report.sampled} items: pruned ${report.pruned.size}, active ${report.kept.size}")
            for ((label, count) in report.kept.take(25)) log("  $count× $label")
        }

        // Revive every CONCEPTS unit that was parked while the vocabulary was empty. Without this the
        // pass skips once and never returns: measured on a live index, a DB reset wiped the prototypes,
        // nothing re-seeded them, and 4,857 items sat at SKIPPED — a gallery with no labels at all and
        // no error anywhere. Seeding is the prerequisite those units were waiting on, so the worker
        // that satisfies it is the right place to requeue them.
        val revived = db.workUnitDao().requeueSkipped(
            ai.rightone.finderplus.model.Pass.CONCEPTS.ordinal, System.currentTimeMillis(),
        )
        if (revived > 0) {
            log("revived $revived skipped CONCEPTS units")
            IndexWorker.enqueue(applicationContext)
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-vocab-seed"
        const val KEY_TUNE = "tune"
        private const val TAG = "finderVocabSeed"
        private const val NOTIF_ID = 4204

        /** CLIP ViT-B/32 embedding width; prototypes must match the stored image vectors. */
        private const val CLIP_DIM = 512

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<VocabularySeedWorker>().build(),
            )
        }
    }
}

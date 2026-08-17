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
import ai.rightone.finderplus.db.vector.Vecs
import ai.rightone.finderplus.model.EmbeddingKind
import ai.rightone.finderplus.vision.ClipTextEncoder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Proves the CLIP **text** tower is aligned with the already-stored **image** vectors.
 *
 * A text encoder that loads without error still tells you nothing: a wrong tokenizer, a mismatched
 * checkpoint, or a missing normalization all produce well-formed vectors in the wrong space, and the
 * only symptom is quietly bad results. So this does not check that encoding *works* — it checks that
 * encoding "a photo of a cat" ranks the gallery's cat pictures above its other pictures, which is the
 * only property anything downstream actually depends on.
 *
 *   adb shell am broadcast -a ai.rightone.finderplus.action.PROBE_CLIP -p <pkg>
 */
@HiltWorker
class ClipProbeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val clipText: ClipTextEncoder,
    private val classifier: ConceptClassifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n: Notification = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Checking visual search")
            .setContentText("Encoding test queries…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())

        val vectors = db.embeddingDao().vectorsOfKind(EmbeddingKind.IMAGE_CLIP.ordinal)
            .mapNotNull { row ->
                val v = Vecs.fromBytes(row.vec)
                // Items whose embedding pass has not run yet are stored as zeros; they would score 0
                // against everything and pollute the ranking with arbitrary ties.
                if (v.isEmpty() || v.all { it == 0f }) null else row.item_id to Vecs.normalized(v)
            }
        log("image vectors: ${vectors.size} usable")
        if (vectors.isEmpty()) {
            log("no usable image embeddings — run the IMAGE_EMBED pass first")
            return Result.success()
        }

        for (query in QUERIES) {
            val q = clipText.encode(query)
            if (q.isEmpty() || q.all { it == 0f }) {
                log("query \"$query\" -> ZERO VECTOR (text tower or vocabulary missing)")
                continue
            }
            var norm = 0f
            for (f in q) norm += f * f
            val top = vectors
                .map { (id, v) -> id to Vecs.dot(q, v) }
                .sortedByDescending { it.second }
                .take(TOP_K)

            log("query \"$query\"  dim=${q.size} L2=${"%.4f".format(kotlin.math.sqrt(norm))}")
            for ((id, score) in top) {
                // The stored profile is what the user would have searched by keyword. Printing it next
                // to a purely visual match makes a wrong space obvious at a glance.
                val profile = db.mediaProfileDao().text(id)?.take(90)?.replace('\n', ' ') ?: "(no profile)"
                log("   %.4f  item=%d  %s".format(score, id, profile))
            }
        }

        // --- Hierarchical recognition on real items, next to what the item already knows about
        // itself. Agreement with the existing ML Kit tags is the readable signal that gating works.
        val prototypes = db.labelPrototypeDao().count()
        log("--- concept classifier (prototypes=$prototypes) ---")
        if (prototypes == 0) {
            log("no prototypes — run SEED_VOCAB first")
            return Result.success()
        }
        for (id in db.mediaItemDao().itemsWithEmbeddingNoUserLabel(CLASSIFY_SAMPLES)) {
            val reading = classifier.read(id)
            val item = db.mediaItemDao().byId(id)
            // Frame count is the thing to watch on video: it is the divisor that turns a set of
            // per-frame guesses into one posterior, and printing it makes an averaging bug obvious.
            val frames = db.embeddingDao()
                .vectorsOfKindFiltered(EmbeddingKind.IMAGE_CLIP.ordinal, listOf(id)).size
            log(
                "item=%d  %s  frames=%d  category=%s(%.2f)".format(
                    id, item?.display_name ?: "?", frames,
                    reading.domain ?: "none", reading.domainConfidence,
                )
            )
            for (c in reading.concepts) {
                val band = when {
                    c.taught -> "learned"
                    c.score >= 0.20f -> "APPLY  "
                    c.score >= 0.10f -> "review "
                    else -> "discard"
                }
                log("    %s %-12s %.3f  %s".format(band, c.domain, c.score, c.label))
            }
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-clip-probe"
        private const val TAG = "finderClipProbe"
        private const val NOTIF_ID = 4203
        private const val TOP_K = 5
        private const val CLASSIFY_SAMPLES = 8

        /** Deliberately ordinary gallery concepts, none of which are stored as tags verbatim. */
        private val QUERIES = listOf(
            "a photo of a cat",
            "a screenshot of a text conversation",
            "a photo of food on a plate",
            "a selfie of a person smiling",
            "a photo of a car",
        )

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ClipProbeWorker>().build(),
            )
        }
    }
}

package ai.rightone.finderplus.index

import android.app.Notification
import android.app.NotificationManager
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
import ai.rightone.finderplus.model.RequiredModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.launch

/**
 * Works through the review backlog with a stronger model, so a thousand queued questions stop being
 * the user's problem.
 *
 * The manual queue measured its own failure: one supervision session left 948 suggestions across 238
 * labels still pending, and the queue refills as indexing continues — an endless job for a person,
 * a bounded one for a judge. The division of labour it implements:
 *
 *  - **Content questions** ("is this a scooter?") go to the judge — verifiable from pixels.
 *  - **Identity questions** (who is this?) are never judged — a name only the user knows.
 *  - **UNSURE stays queued.** The judge answers only what it answers decisively; hedges remain for
 *    the human, so the manual queue becomes the *hard* residue rather than everything.
 *
 * Work is grouped **per item, not per suggestion**: every pending label for an image goes into one
 * batched prompt together with the caption request, so one prefill (the expensive part — minutes on
 * the local judge) answers everything asked about that image.
 *
 * Every verdict lands with machine provenance ([ai.rightone.finderplus.model.TagSource.VLM]) and
 * teaches the prototypes; judged items get their caption rewritten by the same stronger model — the
 * SmolVLM caption was written by a model an order of magnitude smaller.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.JUDGE -p <pkg>
 */
@HiltWorker
class JudgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val groups: ReviewGroups,
    private val localJudge: LocalJudge,
    private val cloudJudge: CloudJudge,
    private val assistPrefs: AssistPrefs,
    private val coordinator: ModelCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIF_ID, notification(0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(done: Int, total: Int): Notification =
        Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("AI is reviewing labels")
            .setContentText(if (total == 0) "Preparing…" else "$done of $total items judged")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .build()

    override suspend fun doWork(): Result {
        // Guarded: on Android 12+ a foreground-service promotion from a backgrounded app throws, and
        // an unguarded throw here killed the worker before its first log line — a judge that failed
        // silently. The work itself does not need the promotion to run; it needs it only to survive
        // doze, which charging-time usage covers anyway.
        runCatching { setForeground(getForegroundInfo()) }

        val judge: Judge = when (assistPrefs.mode) {
            AssistPrefs.Mode.CLOUD -> cloudJudge
            AssistPrefs.Mode.LOCAL -> localJudge
            AssistPrefs.Mode.MANUAL -> return Result.success() // user chose to review themselves
        }
        if (!judge.isReady()) {
            log("judge '${judge.name()}' not ready (model missing or no API key) — nothing to do")
            assistPrefs.noteRunEnd()
            return Result.success()
        }

        val pending = db.contentDao().pendingSuggestions(limit = PER_RUN)
        if (pending.isEmpty()) {
            log("queue empty")
            assistPrefs.noteRunEnd()
            return Result.success()
        }

        // The judge gets the device to itself. A 3.3 GB VLM sharing RAM and GPU with the 256M
        // captioner, CLIP and ASR pushed the process past 4 GB PSS and the system into thrash (load
        // average 19, adb starved). Judging is bursty and finite; indexing is resumable by design —
        // so pause one rather than degrade both.
        val stopIntent = android.content.Intent(IndexControlReceiver.ACTION_STOP)
        stopIntent.setPackage(applicationContext.packageName)
        applicationContext.sendBroadcast(stopIntent)

        val byItem: Map<Long, List<ai.rightone.finderplus.db.entity.TagEntity>> =
            pending.groupBy { it.item_id }
        log("judging ${pending.size} suggestions across ${byItem.size} items with ${judge.name()}")
        assistPrefs.noteRunStart(byItem.size)

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val started = System.currentTimeMillis()
        var yes = 0; var no = 0; var unsure = 0; var recaptioned = 0
        var doneItems = 0; var strikes = 0; var fastFails = 0
        val cache = DecodedImageCache(applicationContext)

        // Pipelined: a producer decodes the next items' bitmaps while the current request is in
        // flight, so the judge sees back-to-back work. Measured before this: pure inference 8-12 s
        // but observed cadence 15-19 s — the difference was the GPU idling through phone-side decode
        // and DB writes. Capacity 2 bounds live bitmaps; only the single producer touches the cache.
        class PreparedItem(val itemId: Long, val labels: List<String>, val bmp: android.graphics.Bitmap, val video: Boolean)

        kotlinx.coroutines.coroutineScope {
        val prepared = kotlinx.coroutines.channels.Channel<PreparedItem>(capacity = 2)
        val producer = launch(kotlinx.coroutines.Dispatchers.IO) {
            for ((itemId, tags) in byItem) {
                if (isStopped) break
                // 640 px, not 1024: every judge shrinks further anyway (cloud 512, local 320), and
                // decode time is the pipeline's rate limit on the phone side.
                val row = db.mediaItemDao().byId(itemId) ?: continue
                val video = row.kind == 1
                val bmp = (if (video) MediaThumbs.load(applicationContext, row.content_uri, 640)
                           else cache.get(row.content_uri, 640)) ?: continue
                prepared.send(PreparedItem(itemId, tags.map { it.label }, bmp, video))
            }
            prepared.close()
        }
        fun cleanup(extra: PreparedItem?) {
            producer.cancel()
            if (extra?.video == true) extra.bmp.recycle()
            while (true) {
                val r = prepared.tryReceive().getOrNull() ?: break
                if (r.video) r.bmp.recycle()
            }
        }

        for (p in prepared) {
            if (isStopped) { cleanup(p); break }
            if (System.currentTimeMillis() - started > RUN_BUDGET_MS) {
                log("run budget spent — ${byItem.size - doneItems} items left for next run")
                cleanup(p)
                break
            }
            val itemId = p.itemId
            val video = p.video
            val bmp = p.bmp

            var caption: String? = null
            var decisive = false
            // Chunked so one prompt never carries so many questions that answers start bleeding into
            // each other; at 1.13 pending labels per item the chunking is almost always a no-op.
            for (chunk in p.labels.chunked(MAX_LABELS_PER_ASK)) {
                if (isStopped) break
                val t0 = System.currentTimeMillis()
                val j = coordinator.withModel(RequiredModel.VLM) { judge.judgeItem(bmp, chunk) }
                val ms = System.currentTimeMillis() - t0

                chunk.forEachIndexed { i, label ->
                    when (j.verdicts[i]) {
                        Verdict.YES -> { groups.judgeAnswer(itemId, label, accept = true); yes++; decisive = true }
                        Verdict.NO -> { groups.judgeAnswer(itemId, label, accept = false); no++; decisive = true }
                        Verdict.UNSURE -> unsure++
                    }
                }
                caption = j.caption ?: caption

                // Same three-strike logic as captioning, but at the judge's own ceiling: the local 4B
                // is *expected* to take minutes per item on this GPU, and holding it to a cloud-shaped
                // ceiling made every local run strike out by design.
                if (ms > judge.perItemCeilingMs()) {
                    if (++strikes >= MAX_STRIKES) { log("judge too slow (${ms}ms) — stopping run"); break }
                } else strikes = 0

                // The opposite failure is just as real: a dead endpoint (adb tunnel dropped, server
                // down) answers in milliseconds with pure UNSURE, and a run once burned its whole
                // batch that way at ~50 ms per item. Real inference takes seconds even when hedging;
                // several consecutive instant all-UNSUREs can only mean nobody is listening. Stop,
                // leave the queue intact, and let the next summon retry against a live endpoint.
                if (ms < FAST_FAIL_MS && j.verdicts.all { it == Verdict.UNSURE }) {
                    if (++fastFails >= MAX_FAST_FAILS) {
                        log("judge endpoint unreachable (${fastFails} instant non-answers) — stopping run")
                        break
                    }
                } else fastFails = 0
            }
            if (fastFails >= MAX_FAST_FAILS) { if (video) bmp.recycle(); cleanup(null); break }
            if (strikes >= MAX_STRIKES) { cleanup(p); break }

            // The caption came free with the verdicts — same inference. Apply it whenever the judge
            // answered *something* decisively; an all-UNSURE reply means it couldn't see this image
            // well, and a caption from that same failed look inherits the doubt.
            if (decisive && !caption.isNullOrBlank()) {
                val docId = db.contentDao().document(itemId, ai.rightone.finderplus.model.DocSource.CAPTION.ordinal)?.id
                    ?: db.contentDao().upsertDocument(
                        ai.rightone.finderplus.db.entity.DocumentEntity(
                            item_id = itemId, source = ai.rightone.finderplus.model.DocSource.CAPTION.ordinal,
                            lang = null, text = "", created_at = System.currentTimeMillis(),
                        )
                    )
                db.contentDao().setDocumentText(docId, caption, null)
                recaptioned++
            }
            if (decisive) runCatching { ItemFinalizer(db).rebuildSearch(itemId) }
            if (video) bmp.recycle()

            doneItems++
            assistPrefs.noteProgress(doneItems, yes, no, unsure)
            runCatching { nm.notify(NOTIF_ID, notification(doneItems, byItem.size)) }
            if (doneItems % 25 == 0) log("progress: $doneItems/${byItem.size} items · $yes yes / $no no / $unsure left-for-human")
        }
        producer.cancel()
        } // coroutineScope

        val mins = (System.currentTimeMillis() - started) / 60000
        log("done in ${mins}min: $yes accepted, $no rejected, $unsure left for human, $recaptioned re-captioned")
        assistPrefs.noteRunEnd()
        // The UNSURE residue: IndexWorker only re-summons the judge when the queue grows past this,
        // which breaks the otherwise-infinite judge→index→judge ping-pong over a static queue.
        assistPrefs.lastQueueSize = db.contentDao().pendingSuggestionCount()

        // Free the ~3 GB context and give the device back to the indexer.
        localJudge.release()
        IndexWorker.enqueue(applicationContext)

        // More waiting and progress was made? Chain another run so the backlog drains unattended.
        if (!isStopped && assistPrefs.lastQueueSize > 0 && yes + no > 0 &&
            assistPrefs.mode != AssistPrefs.Mode.MANUAL
        ) {
            enqueue(applicationContext, chain = true)
        }
        return Result.success()
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        const val UNIQUE_WORK = "finder-judge"
        private const val TAG = "finderJudge"
        private const val NOTIF_ID = 4209

        /** Suggestions per run; the run re-enqueues itself while the backlog and verdicts continue. */
        private const val PER_RUN = 200

        /** Wall-clock per run. Same philosophy as CaptionBudget: spend it, park the rest. */
        private const val RUN_BUDGET_MS = 30L * 60 * 1000

        private const val MAX_STRIKES = 3

        /** An "answer" faster than this with zero verdicts is a connection failure, not a hedge. */
        private const val FAST_FAIL_MS = 2_000L
        private const val MAX_FAST_FAILS = 5

        /** Questions per prompt before answers risk degrading each other. */
        private const val MAX_LABELS_PER_ASK = 6

        /**
         * [chain] matters: a worker re-enqueueing itself from inside its own doWork is still RUNNING,
         * so KEEP sees "existing work" and silently drops the request — measured: run #1 finished with
         * 654 items left and nothing followed. APPEND_OR_REPLACE queues the next run behind the
         * current one; external callers keep KEEP so a broadcast can't double-start a running judge.
         */
        fun enqueue(context: Context, chain: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                if (chain) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<JudgeWorker>().build(),
            )
        }
    }
}

/** How the review queue gets answered: by the user, the on-device judge, or a cloud API. */
@javax.inject.Singleton
class AssistPrefs @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
) {
    enum class Mode { MANUAL, LOCAL, CLOUD }

    private val sp = context.getSharedPreferences("finder-assist", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.MANUAL.name)!!) }.getOrDefault(Mode.MANUAL)
        set(value) = sp.edit().putString("mode", value.name).apply()

    /** Which cloud API answers when [mode] is CLOUD. */
    var provider: CloudProvider
        get() = runCatching { CloudProvider.valueOf(sp.getString("provider", CloudProvider.ANTHROPIC.name)!!) }
            .getOrDefault(CloudProvider.ANTHROPIC)
        set(value) = sp.edit().putString("provider", value.name).apply()

    /**
     * API key for [p], stored locally only, never bundled or logged. Blank disables that provider.
     * Keys are kept per provider so switching providers doesn't destroy the other key.
     */
    fun apiKeyFor(p: CloudProvider): String =
        sp.getString("key_${p.name}", null)
            ?: (if (p == CloudProvider.ANTHROPIC) sp.getString("api_key", "") ?: "" else "") // legacy slot

    fun setApiKeyFor(p: CloudProvider, value: String) =
        sp.edit().putString("key_${p.name}", value.trim()).apply()

    /** Model id for [p]; user-editable, sensible default per provider. */
    fun modelFor(p: CloudProvider): String =
        sp.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() } ?: defaultModelFor(p)

    fun setModelFor(p: CloudProvider, value: String) =
        sp.edit().putString("model_${p.name}", value.trim()).apply()

    /** Convenience views for the currently selected provider — what CloudJudge reads. */
    val apiKey: String get() = apiKeyFor(provider)
    val cloudModel: String get() = modelFor(provider)

    /**
     * Where the user's own Ollama server lives. The default reaches a USB-attached computer through
     * `adb reverse tcp:11434 tcp:11434`; a LAN address works when both are on the same network.
     */
    var ollamaUrl: String
        get() = sp.getString("ollama_url", "http://127.0.0.1:11434")?.takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:11434"
        set(value) = sp.edit().putString("ollama_url", value.trim()).apply()

    /** UNSURE residue after the last judge run; the index only re-summons the judge above this. */
    var lastQueueSize: Int
        get() = sp.getInt("last_queue", 0)
        set(value) = sp.edit().putInt("last_queue", value).apply()

    // ---- Run progress + lifetime stats, written by JudgeWorker, read by the assist UI. ----

    fun noteRunStart(totalItems: Int) = sp.edit()
        .putBoolean("running", true).putInt("run_total", totalItems)
        .putInt("run_done", 0).putInt("run_yes", 0).putInt("run_no", 0).putInt("run_unsure", 0)
        .apply()

    fun noteProgress(done: Int, yes: Int, no: Int, unsure: Int) = sp.edit()
        .putInt("run_done", done).putInt("run_yes", yes).putInt("run_no", no).putInt("run_unsure", unsure)
        .apply()

    fun noteRunEnd() = sp.edit()
        .putBoolean("running", false)
        .putInt("total_yes", sp.getInt("total_yes", 0) + sp.getInt("run_yes", 0))
        .putInt("total_no", sp.getInt("total_no", 0) + sp.getInt("run_no", 0))
        .putLong("last_run_at", System.currentTimeMillis())
        .apply()

    data class Status(
        val running: Boolean,
        val runDone: Int, val runTotal: Int,
        val runYes: Int, val runNo: Int, val runUnsure: Int,
        val totalYes: Int, val totalNo: Int,
        val lastRunAt: Long,
    )

    fun status() = Status(
        running = sp.getBoolean("running", false),
        runDone = sp.getInt("run_done", 0), runTotal = sp.getInt("run_total", 0),
        runYes = sp.getInt("run_yes", 0), runNo = sp.getInt("run_no", 0), runUnsure = sp.getInt("run_unsure", 0),
        totalYes = sp.getInt("total_yes", 0), totalNo = sp.getInt("total_no", 0),
        lastRunAt = sp.getLong("last_run_at", 0L),
    )
}

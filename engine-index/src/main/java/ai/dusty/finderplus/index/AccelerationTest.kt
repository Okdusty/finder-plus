package ai.dusty.finderplus.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.toMediaItem
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.settings.AccelerationSettings
import ai.dusty.finderplus.settings.DescribeSize
import ai.dusty.finderplus.settings.MemorySettings
import ai.dusty.finderplus.settings.ModelHardware
import ai.dusty.finderplus.settings.Pick
import ai.dusty.finderplus.settings.Settings
import ai.dusty.finderplus.speech.ModelManager
import ai.dusty.finderplus.vision.ClipPreprocess
import ai.dusty.finderplus.vision.OrtSessions
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Whether the accelerator earns its place for one model, from measurements on this phone. */
object AccelerationDecision {

    /** What one model did: time per input on each provider, and how alike their outputs were. */
    data class Measured(val cpuMs: List<Long>, val acceleratorMs: List<Long>?, val agreement: Float?)

    /** Cosine similarity the accelerator's output must keep with the processor's, on every input. */
    const val MIN_AGREEMENT = 0.99f

    /** How much faster the accelerator must be; a near tie is not worth a driver that may misbehave. */
    const val MIN_SPEEDUP = 1.1

    fun choose(m: Measured): ModelHardware {
        val acc = m.acceleratorMs?.takeIf { it.isNotEmpty() } ?: return ModelHardware.CPU
        if ((m.agreement ?: 0f) < MIN_AGREEMENT || m.cpuMs.isEmpty()) return ModelHardware.CPU
        return if (median(acc) * MIN_SPEEDUP <= median(m.cpuMs)) ModelHardware.NNAPI else ModelHardware.CPU
    }

    fun median(xs: List<Long>): Double = xs.sorted().let { s -> if (s.isEmpty()) 0.0 else s[s.size / 2].toDouble() }
}

/**
 * "Test on this phone": runs each model that can use the accelerator on real photos and texts from the
 * index, on the processor and through NNAPI, and keeps NNAPI for a model only where it ran the whole
 * model, gave the same results and was clearly faster. Works the same on any chip: nothing is assumed
 * from the chip's name, only measured. Each model runs under the model lock, between indexing steps.
 */
@HiltWorker
class AccelerationTestWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val db: FinderDatabase,
    private val models: ModelManager,
    private val coordinator: ModelCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID.also { IndexWorker.ensureChannel(applicationContext) })
            .setContentTitle("Testing acceleration on this phone")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val images = DecodedImageCache(applicationContext)
        val ids = db.mediaItemDao().itemsWithEmbeddingNoUserLabel(SAMPLES * 3)
        val photos = ArrayList<Bitmap>()
        for (id in ids) {
            if (photos.size >= SAMPLES) break
            val item = db.mediaItemDao().byId(id)?.toMediaItem() ?: continue
            // A copy: the cache recycles its bitmap when it decodes the next one.
            images.get(item.uri, 1024)?.copy(Bitmap.Config.ARGB_8888, false)?.let(photos::add)
        }
        val lines = ArrayList<String>()
        fun note(family: Pick<ModelHardware>, name: String, m: AccelerationDecision.Measured?) {
            if (m == null) { lines += "$name: not installed"; return }
            val choice = AccelerationDecision.choose(m)
            Settings[family] = choice
            val cpu = AccelerationDecision.median(m.cpuMs).toLong()
            val acc = m.acceleratorMs?.takeIf { it.isNotEmpty() }?.let { "${AccelerationDecision.median(it).toLong()} ms" } ?: "cannot run it"
            val agree = m.agreement?.let { " · results %.3f alike".format(it) } ?: ""
            lines += "$name: processor $cpu ms · accelerator $acc$agree → ${choice.label}"
        }

        if (photos.isNotEmpty()) {
            val clip = models.pathOf(ModelCatalog.CLIP_IMAGE.id)
            note(AccelerationSettings.VISUAL, "Visual search", clip?.let { path ->
                coordinator.withModel(RequiredModel.CLIP_IMG) {
                    measure(path, photos.map { longArrayOf(1, 3, ClipPreprocess.SIDE.toLong(), ClipPreprocess.SIDE.toLong()) to ClipPreprocess.toTensorData(it) })
                }
            })
            val order = if (Settings[MemorySettings.DESCRIBE_SIZE] == DescribeSize.BASE) ModelCatalog.florencePreference.reversed()
                else ModelCatalog.florencePreference
            val vision = order.firstNotNullOfOrNull { models.pathOf(it.visionEncoder.id) }
            note(AccelerationSettings.DESCRIBE, "Describe (image part)", vision?.let { path ->
                coordinator.withModel(RequiredModel.VLM) {
                    measure(path, photos.take(DESCRIBE_SAMPLES).map { longArrayOf(1, 3, FLORENCE_SIDE.toLong(), FLORENCE_SIDE.toLong()) to florenceInput(it) })
                }
            })
        } else lines += "No indexed photos to test the image models with yet."

        val texts = ids.mapNotNull { db.mediaProfileDao().text(it)?.take(600) }.take(SAMPLES)
        val weights = models.pathOf(ModelCatalog.TEXT_EMBEDDER_WEIGHTS.id)?.let { models.pathOf(ModelCatalog.TEXT_EMBEDDER.id) }
        val tokenizer = models.pathOf(ModelCatalog.TEXT_EMBEDDER_TOKENIZER.id)
        note(AccelerationSettings.MEANING, "Meaning search", if (texts.isEmpty() || weights == null || tokenizer == null) null else {
            coordinator.withModel(RequiredModel.TEXT_EMB) { measureText(weights, tokenizer, texts) }
        })
        photos.forEach { it.recycle() }

        val report = lines.joinToString("\n")
        applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPORT, report).putLong(KEY_AT, System.currentTimeMillis()).apply()
        android.util.Log.i(TAG, "acceleration test:\n$report")
        return Result.success()
    }

    private fun measure(path: String, inputs: List<Pair<LongArray, FloatArray>>): AccelerationDecision.Measured? =
        ai.dusty.finderplus.vision.AcceleratorBench.measure(path, inputs)?.let { AccelerationDecision.Measured(it.cpuMs, it.acceleratorMs, it.agreement) }

    private suspend fun measureText(weights: String, tokenizer: String, texts: List<String>): AccelerationDecision.Measured {
        fun embedder(p: OrtSessions.Provider) = ai.dusty.finderplus.text.EmbeddingGemmaEmbedder(
            modelPath = { weights }, tokenizerPath = { tokenizer }, modelId = ModelCatalog.TEXT_SPACE_ID, provider = p,
        )
        val cpu = embedder(OrtSessions.Provider.CPU)
        val acc = embedder(OrtSessions.Provider.NNAPI)
        try {
            val cpuMs = ArrayList<Long>(); val accMs = ArrayList<Long>(); var worst = 1f; var accOk = true
            texts.forEachIndexed { i, t ->
                val t0 = System.nanoTime(); val a = cpu.embed(t); val ta = (System.nanoTime() - t0) / 1_000_000
                if (i > 0) cpuMs += ta
                if (accOk) {
                    val t1 = System.nanoTime()
                    val b = runCatching { acc.embed(t) }.getOrNull()
                    if (b == null || b.all { it == 0f }) accOk = false
                    else { if (i > 0) accMs += (System.nanoTime() - t1) / 1_000_000; worst = minOf(worst, cosine(a, b)) }
                }
            }
            return AccelerationDecision.Measured(cpuMs, if (accOk) accMs else null, if (accOk) worst else null)
        } finally {
            cpu.close(); acc.close()
        }
    }

    /** The describe model's image input: the picture at its size, normalized as its processor does. */
    private fun florenceInput(bitmap: Bitmap): FloatArray {
        val s = FLORENCE_SIDE
        val scaled = Bitmap.createScaledBitmap(bitmap, s, s, true)
        val px = IntArray(s * s).also { scaled.getPixels(it, 0, s, 0, 0, s, s) }
        if (scaled !== bitmap) scaled.recycle()
        val plane = s * s
        val out = FloatArray(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            out[i] = (((p shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = (((p shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray) = ai.dusty.finderplus.vision.AcceleratorBench.cosine(a, b)

    companion object {
        private const val TAG = "finderAccel"
        private const val NOTIF_ID = 4231
        private const val UNIQUE = "finder-acceleration-test"
        private const val SAMPLES = 6
        private const val DESCRIBE_SAMPLES = 3
        private const val FLORENCE_SIDE = 768
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        const val PREFS = "finder-acceleration-test"
        const val KEY_REPORT = "report"
        const val KEY_AT = "at"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AccelerationTestWorker>().build(),
            )
        }

        fun running(context: Context): Boolean = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE).get()
                .any { !it.state.isFinished }
        }.getOrDefault(false)
    }
}

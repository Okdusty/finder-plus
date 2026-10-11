package ai.dusty.finderplus.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.ModelSpec
import ai.dusty.finderplus.model.ModelTier
import ai.dusty.finderplus.speech.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * The one place model installs are requested, queued, cancelled and removed.
 *
 * An install is a persisted request, not work tied to a screen: a single [ModelDownloadWorker] drains
 * the queue in order, survives the screen closing and the process dying, and resumes each file where
 * it stopped. Only one worker downloads, one file at a time, because two writers on the same `.part`
 * file corrupt it.
 */
@Singleton
class ModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) {

    private val prefs = context.getSharedPreferences("finder-model-queue", Context.MODE_PRIVATE)

    private val _queue = MutableStateFlow(readQueue())
    /** Top-level model ids waiting or downloading, in order. */
    val queue: StateFlow<List<String>> = _queue.asStateFlow()

    private val _errors = MutableStateFlow(readErrors())
    /** Last failure per model id, cleared when it is retried or installs. */
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** Bumped whenever the set of installed files changes, so screens re-read it. */
    private val _installedVersion = MutableStateFlow(0)
    val installedVersion: StateFlow<Int> = _installedVersion.asStateFlow()

    /** What the worker is doing right now, or null when idle. */
    val progress: Flow<InstallProgress?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE)
            .map { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return@map null
                val p = running.progress
                val id = p.getString(ModelDownloadWorker.KEY_CURRENT) ?: return@map InstallProgress.Starting
                InstallProgress.Downloading(
                    modelId = id,
                    file = p.getString(ModelDownloadWorker.KEY_FILE) ?: id,
                    received = p.getLong(ModelDownloadWorker.KEY_RECEIVED, 0),
                    total = p.getLong(ModelDownloadWorker.KEY_TOTAL, 0),
                    verifying = p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
                )
            }

    fun isInstalled(spec: ModelSpec): Boolean = ModelCatalog.withCompanions(spec).all { models.isInstalled(it.id) }

    /** Bytes still to download for [spec] and its companions, net of anything already on disk. */
    fun remainingBytes(spec: ModelSpec): Long = ModelCatalog.withCompanions(spec)
        .filterNot { models.isInstalled(it.id) }
        .sumOf { (it.sizeBytes - models.partialBytes(it.id)).coerceAtLeast(0) }

    fun bundle(tier: ModelTier): List<ModelSpec> = when (tier) {
        ModelTier.ESSENTIAL -> ModelCatalog.userFacing.filter { it.tier == ModelTier.ESSENTIAL }
        ModelTier.RECOMMENDED -> ModelCatalog.userFacing.filter { it.tier != ModelTier.OPTIONAL }
        ModelTier.OPTIONAL -> ModelCatalog.userFacing
    }

    fun install(specs: List<ModelSpec>) {
        val wanted = specs.filterNot { isInstalled(it) }.map { it.id }
        if (wanted.isEmpty()) return
        synchronized(this) {
            writeQueue((_queue.value + wanted).distinct())
            writeErrors(_errors.value - wanted.toSet())
        }
        ModelDownloadWorker.enqueue(context)
    }

    fun install(spec: ModelSpec) = install(listOf(spec))

    /**
     * Stop installing [id]. The bytes already fetched stay on disk, so a later Install resumes rather
     * than restarting.
     */
    fun cancel(id: String) {
        val wasHead = _queue.value.firstOrNull() == id
        synchronized(this) { writeQueue(_queue.value - id) }
        if (wasHead) {
            WorkManager.getInstance(context).cancelUniqueWork(ModelDownloadWorker.UNIQUE)
            if (_queue.value.isNotEmpty()) ModelDownloadWorker.enqueue(context, afterCancel = true)
        }
    }

    fun cancelAll() {
        synchronized(this) { writeQueue(emptyList()) }
        WorkManager.getInstance(context).cancelUniqueWork(ModelDownloadWorker.UNIQUE)
    }

    /**
     * Remove [spec] and the companions nothing else installed still needs. Shared companions (the
     * speech-detection model every recognizer uses) stay while any other user of them remains.
     */
    fun remove(spec: ModelSpec) {
        cancel(spec.id)
        val keep = ModelCatalog.userFacing
            .filter { it.id != spec.id && isInstalled(it) }
            .flatMap { ModelCatalog.withCompanions(it) }
            .map { it.id }
            .toSet()
        for (s in ModelCatalog.withCompanions(spec)) if (s.id !in keep) models.delete(s.id)
        notifyInstalledChanged()
    }

    /** True on Wi-Fi/Ethernet; false on mobile data or when there is no network at all. */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun freeBytes(): Long = models.freeBytes()

    fun footprintBytes(): Long = models.installedFootprintBytes()

    // ---- called by the worker ----

    internal fun head(): String? = _queue.value.firstOrNull()

    internal fun completed(id: String) {
        synchronized(this) {
            writeQueue(_queue.value - id)
            writeErrors(_errors.value - id)
        }
        notifyInstalledChanged()
    }

    internal fun failed(id: String, message: String) {
        synchronized(this) {
            writeQueue(_queue.value - id)
            writeErrors(_errors.value + (id to message))
        }
    }

    internal fun notifyInstalledChanged() { _installedVersion.value++ }

    // ---- persistence: a comma list keeps order, which a string set would not ----

    private fun readQueue(): List<String> =
        prefs.getString(KEY_QUEUE, "").orEmpty().split(',').filter { it.isNotBlank() }

    private fun writeQueue(q: List<String>) {
        prefs.edit().putString(KEY_QUEUE, q.joinToString(",")).apply()
        _queue.value = q
    }

    private fun readErrors(): Map<String, String> =
        prefs.getStringSet(KEY_ERRORS, emptySet()).orEmpty().mapNotNull {
            val i = it.indexOf('\u0001')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()

    private fun writeErrors(e: Map<String, String>) {
        prefs.edit().putStringSet(KEY_ERRORS, e.map { (k, v) -> "$k\u0001$v" }.toSet()).apply()
        _errors.value = e
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_ERRORS = "errors"
    }
}

/** What the background installer is doing, for the screens that show it. */
sealed interface InstallProgress {
    data object Starting : InstallProgress
    data class Downloading(
        val modelId: String,
        val file: String,
        val received: Long,
        val total: Long,
        val verifying: Boolean,
    ) : InstallProgress {
        val fraction: Float get() = if (total <= 0) 0f else (received.toFloat() / total).coerceIn(0f, 1f)
    }
}

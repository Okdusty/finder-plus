package ai.dusty.finderplus.speech

import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.DownloadProgress
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.ModelRole
import ai.dusty.finderplus.model.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs / verifies / deletes optional on-device models. The only component that touches the
 * network, and only for one-time downloads — indexing itself runs offline.
 * See docs/design/03-AI-PIPELINE.md §5.
 */
interface ModelManager {
    fun catalog(): List<ModelSpec>
    fun isInstalled(id: String): Boolean
    fun pathOf(id: String): String?
    suspend fun download(id: String): Flow<DownloadProgress>
    fun delete(id: String)
    fun installedFootprintBytes(): Long

    /** Bytes already on disk from an interrupted download of [id] — it resumes from there. */
    fun partialBytes(id: String): Long = 0L

    /** Free space where models are stored. */
    fun freeBytes(): Long = Long.MAX_VALUE

    /** The selected speech model, or null when its files (model + projector) are not both present. */
    fun asrConfig(accelerator: Accelerator = Accelerator.GPU_VULKAN): AsrConfig?

    /** Which native engine the selected model requires. */
    fun selectedEngine(): SpeechEngine

    /** Id of the ASR model the user chose; defaults to the 0.6B tier. */
    var selectedAsrId: String

    /**
     * The speech model that will actually run: the selected one when installed, otherwise the first
     * installed one in [ModelCatalog.asrPreference]. Null when no speech model is installed.
     */
    fun effectiveAsrId(): String? = selectedAsrId
}

/**
 * Why a download stopped, in words Settings can show as-is. [retryable] separates a network that will
 * come back (retry with backoff, keep the queue) from a definite answer (no space, bad checksum, gone).
 */
class ModelDownloadException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : IOException(message, cause)

/**
 * Stores models under filesDir/models/<id>.<ext> (or [ModelSpec.fileName]).
 *
 * Every download is resumable, space-checked up front and, when the catalog pins a checksum, SHA-256
 * verified before it is renamed into place. A file that fails verification is deleted, never
 * installed: a truncated or tampered model either crashes native code or quietly produces wrong
 * vectors.
 */
class FileModelManager(
    private val modelsDir: File,
    private val prefs: ModelPrefs,
) : ModelManager {

    init { modelsDir.mkdirs() }

    override var selectedAsrId: String
        get() = prefs.getSelectedAsrId() ?: ModelCatalog.defaultAsr.id
        set(value) = prefs.setSelectedAsrId(value)

    override fun catalog(): List<ModelSpec> = ModelCatalog.all

    override fun isInstalled(id: String): Boolean {
        val spec = spec(id) ?: return false
        val f = fileFor(id)
        // A partial download must not count as installed. The 2% tolerance allows for upstream
        // re-exports whose size differs slightly; hashed downloads are verified exactly before they
        // reach this path.
        return f.isFile && f.length() >= (spec.sizeBytes * 0.98).toLong()
    }

    override fun pathOf(id: String): String? = fileFor(id).takeIf { isInstalled(id) }?.absolutePath

    override fun partialBytes(id: String): Long = partFor(id).takeIf { it.isFile }?.length() ?: 0L

    override fun freeBytes(): Long = runCatching { modelsDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    override fun effectiveAsrId(): String? {
        val selected = selectedAsrId
        if (asrUsable(selected)) return selected
        return ModelCatalog.asrPreference.firstOrNull { asrUsable(it.id) }?.id
    }

    /** A speech model is usable when it and every companion it needs are on disk. */
    private fun asrUsable(id: String): Boolean {
        val s = spec(id) ?: return false
        return ModelCatalog.withCompanions(s).all { isInstalled(it.id) }
    }

    override fun asrConfig(accelerator: Accelerator): AsrConfig? {
        val model = effectiveAsrId()?.let(::spec) ?: return null
        val modelPath = pathOf(model.id) ?: return null
        // Whisper is a single self-contained file. Requiring a projector would make every Whisper model
        // resolve to "not installed".
        if (model.role == ModelRole.ASR_WHISPER) {
            return AsrConfig(modelPath = modelPath, projectorPath = "", accelerator = accelerator)
        }
        val projectorId = model.requiresId ?: return null
        val projPath = pathOf(projectorId) ?: return null
        return AsrConfig(
            modelPath = modelPath,
            projectorPath = projPath,
            accelerator = accelerator,
        )
    }

    /** Which engine the effective model needs. Drives the [SpeechRecognizer] chosen at runtime. */
    override fun selectedEngine(): SpeechEngine =
        if (effectiveAsrId()?.let(::spec)?.role == ModelRole.ASR_WHISPER) SpeechEngine.WHISPER else SpeechEngine.QWEN3

    override suspend fun download(id: String): Flow<DownloadProgress> = flow {
        // One writer per process: a cancelled download's coroutine can still be finishing its last
        // write when a fresh attempt starts, and two writers on one .part file corrupt it.
        downloadLock.withLock { downloadLocked(id) }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.downloadLocked(id: String) {
        val spec = spec(id) ?: throw ModelDownloadException("Unknown model $id")
        if (spec.url.isEmpty()) throw ModelDownloadException("No download is available for ${spec.label}")
        if (isInstalled(id)) {
            emit(DownloadProgress(spec.sizeBytes, spec.sizeBytes, verifying = false))
            return
        }

        val target = fileFor(id)
        target.parentFile?.mkdirs()
        val part = partFor(id)
        var existing = if (part.isFile) part.length() else 0L

        // Refuse up front rather than fail part-way: filling the disk mid-write can break other app
        // data too.
        val needed = (spec.sizeBytes - existing).coerceAtLeast(0) + SPACE_MARGIN
        val free = freeBytes()
        if (free < needed) {
            throw ModelDownloadException(
                "Not enough storage for ${spec.label}: needs ${mb(needed)}, ${mb(free)} free"
            )
        }

        val digest = spec.sha256.takeIf { it.isNotEmpty() }?.let { MessageDigest.getInstance("SHA-256") }

        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "finderplus-model-manager")
            // Resume a partial download rather than restarting a multi-hundred-MB transfer.
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = try {
                conn.connect(); conn.responseCode
            } catch (t: IOException) {
                throw ModelDownloadException("Can't reach the download server — check your connection", t, retryable = true)
            }

            when {
                // Everything is already here; the server says there is nothing past the end.
                code == 416 && existing > 0 -> Unit
                code == HttpURLConnection.HTTP_OK && existing > 0 -> {
                    existing = 0L // server ignored the range; start over
                    part.delete()
                }
                code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK -> Unit
                code == 404 -> throw ModelDownloadException("${spec.label} is no longer at its download address")
                code >= 500 || code == 429 -> throw ModelDownloadException("The download server is busy (HTTP $code) — retrying", retryable = true)
                else -> throw ModelDownloadException("Download refused (HTTP $code)")
            }

            // The hash covers the whole file, so bytes kept from an earlier attempt are fed in first.
            if (digest != null && existing > 0) {
                part.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
                }
            }

            val total = when {
                code == 416 -> existing
                existing > 0 && code == HttpURLConnection.HTTP_PARTIAL -> existing + conn.contentLengthLong
                else -> conn.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
            }
            emit(DownloadProgress(existing, total, verifying = false))

            if (code != 416) {
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, existing > 0).use { output ->
                        val buf = ByteArray(1 shl 16)
                        var written = existing
                        var lastEmit = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive() // Cancel keeps the .part for resume
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 400) {
                                lastEmit = now
                                emit(DownloadProgress(written, total, verifying = false))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        emit(DownloadProgress(part.length(), part.length(), verifying = true))
        if (digest != null) {
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                throw ModelDownloadException("${spec.label} failed its integrity check and was discarded — try again")
            }
        } else if (part.length() < (spec.sizeBytes * 0.98).toLong()) {
            throw ModelDownloadException("${spec.label} download ended early — tap Install to resume")
        }
        target.delete()
        if (!part.renameTo(target)) throw ModelDownloadException("Could not save ${spec.label}")
        emit(DownloadProgress(target.length(), target.length(), verifying = false))
    }

    override fun delete(id: String) {
        val f = fileFor(id)
        f.delete()
        partFor(id).delete()
        // ONNX Runtime's optimized-graph cache sits beside the model and can be as large as it.
        File(f.parentFile, "${f.name}.opt").delete()
        File(f.parentFile, "${f.name}.fbin").delete() // the tokenizer's compact cache
        f.parentFile?.takeIf { it != modelsDir && it.list()?.isEmpty() == true }?.delete()
    }

    override fun installedFootprintBytes(): Long =
        modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun spec(id: String) = ModelCatalog.byId(id)

    private fun partFor(id: String): File = fileFor(id).let { File(it.parentFile, "${it.name}.part") }

    /** GGUF for everything that runs on llama.cpp (ASR and VLM, plus their projectors); "bin" for the
     *  rest. Getting this wrong silently makes an installed model invisible. */
    private fun fileFor(id: String): File {
        spec(id)?.fileName?.let { return File(modelsDir, it) }
        val ext = when (spec(id)?.role) {
            ModelRole.ASR, ModelRole.ASR_PROJECTOR, ModelRole.VLM, ModelRole.VLM_PROJECTOR -> "gguf"
            // whisper.cpp ships single-file ggml models with a .bin name and no companion projector.
            ModelRole.ASR_WHISPER -> "bin"
            else -> "bin"
        }
        return File(modelsDir, "$id.$ext")
    }

    private companion object {
        val downloadLock = kotlinx.coroutines.sync.Mutex()

        /** Headroom left on the disk after a download, so the device itself never runs dry. */
        const val SPACE_MARGIN = 300L * 1024 * 1024

        fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    }
}

/** Tiny persistence seam so ai-speech does not depend on Android preferences APIs directly. */
interface ModelPrefs {
    fun getSelectedAsrId(): String?
    fun setSelectedAsrId(id: String)
}

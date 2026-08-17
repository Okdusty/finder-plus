package ai.dusty.finderplus.speech

import ai.dusty.finderplus.model.Accelerator
import ai.dusty.finderplus.model.DownloadProgress
import ai.dusty.finderplus.model.ModelCatalog
import ai.dusty.finderplus.model.ModelRole
import ai.dusty.finderplus.model.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    /** The selected speech model, or null when its files (model + projector) are not both present. */
    fun asrConfig(accelerator: Accelerator = Accelerator.GPU_VULKAN): AsrConfig?

    /** Which native engine the selected model requires. */
    fun selectedEngine(): SpeechEngine


    /** Id of the ASR model the user chose; defaults to the 0.6B tier. */
    var selectedAsrId: String
}

/** Stores models under filesDir/models/<id>.gguf. Downloads are resumable and length-checked. */
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
        // A partially downloaded file must not count as installed.
        return f.exists() && f.length() >= (spec.sizeBytes * 0.98).toLong()
    }

    override fun pathOf(id: String): String? = fileFor(id).takeIf { isInstalled(id) }?.absolutePath

    override fun asrConfig(accelerator: Accelerator): AsrConfig? {
        val model = spec(selectedAsrId) ?: return null
        val modelPath = pathOf(model.id) ?: return null
        // Whisper is a single self-contained file. Requiring a projector here is what made the Qwen3
        // path correct and would have made every Whisper selection silently resolve to "not installed".
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

    /** Which engine the currently selected model needs. Drives the [SpeechRecognizer] chosen at runtime. */
    override fun selectedEngine(): SpeechEngine =
        if (spec(selectedAsrId)?.role == ModelRole.ASR_WHISPER) SpeechEngine.WHISPER else SpeechEngine.QWEN3

    override suspend fun download(id: String): Flow<DownloadProgress> = flow {
        val spec = spec(id) ?: throw IllegalArgumentException("unknown model $id")
        require(spec.url.isNotEmpty()) { "no download URL configured for $id" }

        val target = fileFor(id)
        val part = File(target.parentFile, "${target.name}.part")
        var existing = if (part.exists()) part.length() else 0L

        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            // Resume a partial download rather than restarting a multi-hundred-MB transfer.
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()

        if (conn.responseCode == HttpURLConnection.HTTP_OK && existing > 0) {
            existing = 0L // server ignored the range; start over
            part.delete()
        }
        val total = if (existing > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL) {
            existing + conn.contentLengthLong
        } else {
            conn.contentLengthLong.takeIf { it > 0 } ?: spec.sizeBytes
        }

        emit(DownloadProgress(existing, total, verifying = false))
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, existing > 0).use { output ->
                val buf = ByteArray(1 shl 16)
                var written = existing
                var lastEmit = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 400) {
                        lastEmit = now
                        emit(DownloadProgress(written, total, verifying = false))
                    }
                }
            }
        }
        emit(DownloadProgress(total, total, verifying = true))
        if (!part.renameTo(target)) throw java.io.IOException("could not finalize $id")
        emit(DownloadProgress(total, total, verifying = false))
    }.flowOn(Dispatchers.IO)

    override fun delete(id: String) {
        fileFor(id).delete()
        File(modelsDir, "${fileFor(id).name}.part").delete()
    }

    override fun installedFootprintBytes(): Long = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun spec(id: String) = ModelCatalog.all.firstOrNull { it.id == id }

    /** GGUF for everything that runs on llama.cpp (ASR and VLM, plus their projectors); ONNX-ish "bin"
     *  for the rest. Getting this wrong silently makes an installed model invisible. */
    private fun fileFor(id: String): File {
        val ext = when (spec(id)?.role) {
            ModelRole.ASR, ModelRole.ASR_PROJECTOR, ModelRole.VLM, ModelRole.VLM_PROJECTOR -> "gguf"
            // whisper.cpp ships single-file ggml models with a .bin name and no companion projector.
            ModelRole.ASR_WHISPER -> "bin"
            else -> "bin"
        }
        return File(modelsDir, "$id.$ext")
    }
}

/** Tiny persistence seam so ai-speech does not depend on Android preferences APIs directly. */
interface ModelPrefs {
    fun getSelectedAsrId(): String?
    fun setSelectedAsrId(id: String)
}

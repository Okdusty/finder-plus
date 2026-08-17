package ai.rightone.finderplus.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ai.rightone.finderplus.model.MediaKind
import ai.rightone.finderplus.model.SearchResult
import java.io.File

/**
 * Copies a tapped search result to the system clipboard as the **actual media**, not its metadata.
 *
 * The original bytes are copied into `cache/clip/` and shared through a [FileProvider], so the
 * pasting app reads a real image file (correct MIME, no media permission needed) — pasting into a
 * chat, editor or document yields the picture itself. Extracted text is NOT put on the clipboard;
 * it is available explicitly via [copyText] (long-press → Copy text).
 */
class ClipboardWriter(private val context: Context) {

    /** Copy the media itself. Returns the toast label. */
    fun copyResult(result: SearchResult): String {
        val item = result.item
        val label = item.displayName ?: "media"
        val mime = item.mime ?: defaultMime(item.kind)
        return try {
            val shareUri = stageForSharing(Uri.parse(item.uri), label)
            if (shareUri == null) {
                // Fall back to the source URI — but never hand out a raw vault blob, which the
                // receiving app would paste as unreadable ciphertext.
                if (ai.rightone.finderplus.media.VaultCrypto.isVaultFile(Uri.parse(item.uri).path)) {
                    return "Couldn't copy $label"
                }
                setClip(clipOf(label, mime, Uri.parse(item.uri)))
                "Copied $label"
            } else {
                setClip(clipOf(label, mime, shareUri))
                when (item.kind) {
                    MediaKind.IMAGE -> "Copied image · $label"
                    MediaKind.VIDEO -> "Copied video · $label"
                    MediaKind.AUDIO -> "Copied audio · $label"
                }
            }
        } catch (_: Exception) {
            "Couldn't copy $label"
        }
    }

    /** Explicit action: copy the extracted text (transcript / OCR / AI-revision profile). */
    fun copyText(text: String, label: String = "content"): String {
        setClip(ClipData.newPlainText(label, text))
        return "Copied text"
    }

    /**
     * Duplicate the media into cache/clip so it can be handed out via FileProvider. Copying (rather
     * than re-encoding) keeps the original pixels/quality byte-for-byte.
     */
    private fun stageForSharing(source: Uri, label: String): Uri? {
        val dir = File(context.cacheDir, "clip").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the most recent copy is ever needed
        val safeName = label.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(96).ifEmpty { "media" }
        val target = File(dir, safeName)
        // Vaulted media decrypts on the way out: the clipboard must carry the picture, not ciphertext.
        ai.rightone.finderplus.media.VaultIO.openInputStream(context, source.toString())?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: return null
        if (!target.exists() || target.length() == 0L) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.clips", target)
    }

    private fun clipOf(label: String, mime: String, uri: Uri): ClipData =
        ClipData(ClipDescription(label, arrayOf(mime)), ClipData.Item(uri))

    private fun setClip(clip: ClipData) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun defaultMime(kind: MediaKind) = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.VIDEO -> "video/*"
        MediaKind.AUDIO -> "audio/*"
    }
}

package ai.dusty.finderplus.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Bridges vaulted media to the outside world.
 *
 * A vaulted file is an encrypted `.fpv` blob: no other app can read it, which is the point — but
 * "Open" and "Copy" exist to hand media *to* other apps. So those two actions stage a **decrypted
 * copy** in this app's cache and share that through the FileProvider, exactly as the clipboard has
 * always done for gallery media.
 *
 * The staging directory is wiped on every call: a plaintext copy exists only for as long as the app
 * the user just handed it to needs it, never as a growing shadow of the vault.
 */
object VaultAccess {

    /**
     * A URI another app can actually read: gallery media passes through unchanged, vaulted media is
     * decrypted to cache first. Null when staging fails.
     */
    fun shareableUri(context: Context, uri: String, displayName: String?): Uri? {
        val parsed = Uri.parse(uri)
        val path = parsed.path
        if (parsed.scheme != "file" || !ai.dusty.finderplus.media.VaultCrypto.isVaultFile(path)) return parsed

        return runCatching {
            ai.dusty.finderplus.media.VaultCrypto.init(context)
            val dir = File(context.cacheDir, "vault-open").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            // Name the plaintext copy after the original file, so the receiving app sees a sane
            // filename and extension rather than something.jpg.fpv.
            val name = (displayName ?: File(path!!).name.removeSuffix(ai.dusty.finderplus.media.VaultCrypto.EXT))
                .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(96).ifEmpty { "media" }
            val staged = File(dir, name)
            ai.dusty.finderplus.media.VaultCrypto.openDecrypting(File(path!!)).use { input ->
                staged.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
            if (!staged.exists() || staged.length() == 0L) return null
            FileProvider.getUriForFile(context, "${context.packageName}.clips", staged)
        }.getOrNull()
    }
}

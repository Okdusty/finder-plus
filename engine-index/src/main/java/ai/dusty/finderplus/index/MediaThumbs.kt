package ai.rightone.finderplus.index

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size

/**
 * One representative bitmap for any media kind.
 *
 * Images decode through the existing cache; this exists for **video**, where `BitmapFactory` cannot
 * read the container and the right answer is the platform thumbnail — the same frame the user sees in
 * their gallery app, which is exactly what "label the thumbnail" should label.
 */
object MediaThumbs {

    fun load(context: Context, uri: String, maxEdgePx: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        // Vaulted media has no MediaStore row, so loadThumbnail cannot serve it — pull the frame
        // straight from the file instead.
        if (parsed.scheme == "file") {
            ai.rightone.finderplus.media.VaultCrypto.init(context)
            val f = java.io.File(parsed.path!!)
            val r = MediaMetadataRetriever()
            return@runCatching try {
                // Encrypted blobs are served through a seeking decryptor, so a frame grab decrypts
                // only the bytes it touches instead of the whole video.
                if (ai.rightone.finderplus.media.VaultCrypto.isVaultFile(parsed.path)) {
                    r.setDataSource(ai.rightone.finderplus.media.VaultCrypto.dataSource(f))
                } else r.setDataSource(parsed.path)
                r.frameAtTime
            } finally {
                r.release()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(parsed, Size(maxEdgePx, maxEdgePx), null)
        } else {
            // Pre-29 fallback: pull the first frame ourselves.
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, Uri.parse(uri))
                r.frameAtTime
            } finally {
                r.release()
            }
        }
    }.getOrNull()
}

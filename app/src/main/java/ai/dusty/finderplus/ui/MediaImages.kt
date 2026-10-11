package ai.dusty.finderplus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size

/**
 * Thumbnails for MediaStore media (`content://`) and vaulted media (`file://`). `loadThumbnail` throws
 * on file URIs, so vault files are decoded from disk: sampled for images, first frame for video.
 */
object MediaImages {

    fun thumbnail(context: Context, uri: String, px: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "file") {
            return@runCatching context.contentResolver.loadThumbnail(parsed, Size(px, px), null)
        }
        val path = parsed.path ?: return@runCatching null
        ai.dusty.finderplus.media.VaultCrypto.init(context)
        val encrypted = ai.dusty.finderplus.media.VaultCrypto.isVaultFile(path)
        val file = java.io.File(path)

        // Image first: cheap bounds probe + sampled decode, reading through the decryptor when the
        // file is a vault blob. Falls through to a video frame grab.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (encrypted) {
            ai.dusty.finderplus.media.VaultCrypto.openDecrypting(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        } else BitmapFactory.decodeFile(path, bounds)

        if (bounds.outWidth > 0) {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = (if (encrypted) {
                ai.dusty.finderplus.media.VaultCrypto.openDecrypting(file).use { BitmapFactory.decodeStream(it, null, opts) }
            } else BitmapFactory.decodeFile(path, opts)) ?: return@runCatching null
            // Hidden photos keep their EXIF orientation inside the blob; show them upright too.
            return@runCatching ai.dusty.finderplus.media.Bitmaps.oriented(raw, ai.dusty.finderplus.media.Bitmaps.exifOrientation(context, uri))
        }
        val r = MediaMetadataRetriever()
        try {
            if (encrypted) r.setDataSource(ai.dusty.finderplus.media.VaultCrypto.dataSource(file))
            else r.setDataSource(path)
            r.frameAtTime
        } finally {
            r.release()
        }
    }.getOrNull()
}

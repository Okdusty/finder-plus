package ai.dusty.finderplus.index

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

    /** A video frame and where it is in the video; [atMs] is null for the platform thumbnail, whose time is not known. */
    class VideoFrame(val bitmap: Bitmap, val atMs: Long?)

    /**
     * The frame a video is described from: its most typical keyframe (see [MediaMoments.typical]), so
     * the names and caption cover what most of the video shows and a preview can open there. Falls back
     * to the platform thumbnail while the keyframes have no vectors or times.
     */
    suspend fun describedFrame(
        context: Context,
        embeddings: ai.dusty.finderplus.db.dao.EmbeddingDao,
        itemId: Long,
        uri: String,
        maxEdgePx: Int,
    ): VideoFrame? {
        val choice = ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.MediaSettings.VIDEO_DESCRIBE_FRAME]
        if (choice == ai.dusty.finderplus.settings.DescribeFrame.THUMBNAIL) return load(context, uri, maxEdgePx)?.let { VideoFrame(it, null) }
        val frames = embeddings.vectorsOfKindFiltered(ai.dusty.finderplus.model.EmbeddingKind.IMAGE_CLIP.ordinal, listOf(itemId))
            .mapNotNull { r -> r.ts_ms?.takeIf { it >= 0 }?.let { it to ai.dusty.finderplus.db.vector.Vecs.fromBytes(r.vec) } }
        MediaMoments.typical(frames)?.let { at -> frameAt(context, uri, at, maxEdgePx)?.let { return VideoFrame(it, at) } }
        return load(context, uri, maxEdgePx)?.let { VideoFrame(it, null) }
    }

    /** A video's frame nearest [ms], fitted into [maxEdgePx]; null when it cannot be read. */
    fun frameAt(context: Context, uri: String, ms: Long, maxEdgePx: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        val r = MediaMetadataRetriever()
        try {
            val path = parsed.path
            if (parsed.scheme == "file" && path != null) {
                ai.dusty.finderplus.media.VaultCrypto.init(context)
                if (ai.dusty.finderplus.media.VaultCrypto.isVaultFile(path)) {
                    r.setDataSource(ai.dusty.finderplus.media.VaultCrypto.dataSource(java.io.File(path)))
                } else r.setDataSource(path)
            } else r.setDataSource(context, parsed)
            val us = ms * 1000
            if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxEdgePx, maxEdgePx)
            } else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            r.release()
        }
    }.getOrNull()

    fun load(context: Context, uri: String, maxEdgePx: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        // Vaulted media has no MediaStore row, so loadThumbnail cannot serve it — pull the frame
        // straight from the file instead.
        if (parsed.scheme == "file") {
            ai.dusty.finderplus.media.VaultCrypto.init(context)
            val f = java.io.File(parsed.path!!)
            val r = MediaMetadataRetriever()
            return@runCatching try {
                // Encrypted blobs are served through a seeking decryptor, so a frame grab decrypts
                // only the bytes it touches instead of the whole video.
                if (ai.dusty.finderplus.media.VaultCrypto.isVaultFile(parsed.path)) {
                    r.setDataSource(ai.dusty.finderplus.media.VaultCrypto.dataSource(f))
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

package ai.dusty.finderplus.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import ai.dusty.finderplus.model.MediaItem
import ai.dusty.finderplus.model.MediaKind

/** A lightweight change-detection digest for the incremental diff — avoids reading full rows. */
data class MediaDigest(
    val id: Long,
    val kind: MediaKind,
    val dateModified: Long,
    val generation: Long,
    val sizeBytes: Long,
)

interface MediaStoreReader {
    /** Current MediaStore generation for the external volume; used to fast-skip an unchanged scan. */
    fun currentGeneration(): Long

    /** Cheap projection of every visible media file (id + change-detection columns). */
    fun digest(): List<MediaDigest>

    /** Full metadata for one file, resolved lazily when the item is (re)indexed. */
    fun read(id: Long, kind: MediaKind): MediaItem?

    /**
     * Full metadata for EVERY visible file in one query per volume (3 total). Used by the scanner so
     * a first index does not run thousands of per-item queries — the dominant cost otherwise.
     */
    fun readAll(): List<MediaItem>
}

/**
 * MediaStore-backed reader. Honors partial photo access (Android 14 READ_MEDIA_VISUAL_USER_SELECTED)
 * implicitly: the query simply returns fewer rows. See docs/design/01-DB-ENGINE.md §8.
 */
class AndroidMediaStoreReader(private val context: Context) : MediaStoreReader {

    private val volumes = listOf(
        Triple(MediaKind.IMAGE, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media._ID),
        Triple(MediaKind.VIDEO, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media._ID),
        Triple(MediaKind.AUDIO, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media._ID),
    )

    override fun currentGeneration(): Long =
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)

    override fun digest(): List<MediaDigest> {
        val out = ArrayList<MediaDigest>(1024)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        )
        for ((kind, uri, _) in volumes) {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dmCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val genCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
                val szCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    out += MediaDigest(
                        id = c.getLong(idCol),
                        kind = kind,
                        dateModified = c.getLong(dmCol),
                        generation = c.getLong(genCol),
                        sizeBytes = c.getLong(szCol),
                    )
                }
            }
        }
        return out
    }

    // Per-kind projection: the Images table has no DURATION, and the Audio table has no
    // DATE_TAKEN/WIDTH/HEIGHT — querying a non-existent column throws. The reads are null-safe, so
    // columns simply absent from the projection resolve to null.
    private fun projectionFor(kind: MediaKind): Array<String> {
        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        when (kind) {
            MediaKind.IMAGE -> cols += listOf(
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            )
            MediaKind.VIDEO -> cols += listOf(
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
            )
            MediaKind.AUDIO -> cols += MediaStore.MediaColumns.DURATION
        }
        return cols.toTypedArray()
    }

    private fun cursorToItem(c: android.database.Cursor, kind: MediaKind, baseUri: android.net.Uri): MediaItem {
        fun longOrNull(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getLong(it) }
        fun intOrNull(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getInt(it) }
        fun strOrNull(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }
            ?.let { if (c.isNull(it)) null else c.getString(it) }
        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
        return MediaItem(
            id = id,
            uri = ContentUris.withAppendedId(baseUri, id).toString(),
            kind = kind,
            displayName = strOrNull(MediaStore.MediaColumns.DISPLAY_NAME),
            mime = strOrNull(MediaStore.MediaColumns.MIME_TYPE),
            sizeBytes = longOrNull(MediaStore.MediaColumns.SIZE) ?: 0L,
            dateTakenMs = longOrNull(MediaStore.MediaColumns.DATE_TAKEN),
            dateModified = longOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
            durationMs = longOrNull(MediaStore.MediaColumns.DURATION),
            width = intOrNull(MediaStore.MediaColumns.WIDTH),
            height = intOrNull(MediaStore.MediaColumns.HEIGHT),
            lat = null, // filled by ai-vision from EXIF during the METADATA pass
            lon = null,
            place = null,
            bucketId = longOrNull(MediaStore.MediaColumns.BUCKET_ID),
            bucketName = strOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            indexState = ai.dusty.finderplus.model.IndexState.NEW,
            pipelineVersion = 0,
        )
    }

    override fun read(id: Long, kind: MediaKind): MediaItem? {
        val (_, baseUri, _) = volumes.first { it.first == kind }
        val itemUri = ContentUris.withAppendedId(baseUri, id)
        context.contentResolver.query(itemUri, projectionFor(kind), null, null, null)?.use { c ->
            if (c.moveToFirst()) return cursorToItem(c, kind, baseUri)
        }
        return null
    }

    override fun readAll(): List<MediaItem> {
        val out = ArrayList<MediaItem>(4096)
        for ((kind, uri, _) in volumes) {
            context.contentResolver.query(uri, projectionFor(kind), null, null, null)?.use { c ->
                while (c.moveToNext()) out += cursorToItem(c, kind, uri)
            }
        }
        return out
    }
}

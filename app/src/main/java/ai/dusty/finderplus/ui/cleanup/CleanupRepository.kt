package ai.dusty.finderplus.ui.cleanup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.db.vector.Vecs
import ai.dusty.finderplus.media.DuplicateFinder
import ai.dusty.finderplus.media.MediaFile
import ai.dusty.finderplus.model.EmbeddingKind
import ai.dusty.finderplus.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data for the clean-up screen: every visible media file read from MediaStore (so it works before
 * anything is indexed), fingerprints cached so a repeat visit reads nothing, and the CLIP vectors the
 * indexer already stored, for look-alike detection.
 */
@Singleton
class CleanupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FinderDatabase,
) {

    fun hasMediaAccess(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Every photo, video and audio file the app can see. One query per table, each with its own
     * projection: the columns differ per table (images have no DURATION, audio no DATE_TAKEN), and a
     * shared projection throws.
     */
    fun loadMedia(): List<MediaFile> {
        val out = ArrayList<MediaFile>()
        val base = listOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
        )
        data class Table(val uri: Uri, val kind: MediaKind, val extra: List<String>)
        val tables = listOf(
            Table(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE,
                listOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)),
            Table(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO,
                listOf(MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DURATION)),
            Table(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaKind.AUDIO, listOf(MediaStore.Audio.Media.DURATION)),
        )
        for (t in tables) {
            runCatching {
                context.contentResolver.query(t.uri, (base + t.extra).toTypedArray(), null, null, null)?.use { c ->
                    fun col(name: String) = c.getColumnIndex(name)
                    val id = col(MediaStore.MediaColumns._ID); val name = col(MediaStore.MediaColumns.DISPLAY_NAME)
                    val size = col(MediaStore.MediaColumns.SIZE); val added = col(MediaStore.MediaColumns.DATE_ADDED)
                    val modified = col(MediaStore.MediaColumns.DATE_MODIFIED); val rel = col(MediaStore.MediaColumns.RELATIVE_PATH)
                    val mime = col(MediaStore.MediaColumns.MIME_TYPE); val taken = col("datetaken")
                    val w = col("width"); val h = col("height"); val dur = col("duration")
                    while (c.moveToNext()) {
                        val rowId = c.getLong(id)
                        out += MediaFile(
                            id = rowId,
                            uri = ContentUris.withAppendedId(t.uri, rowId).toString(),
                            kind = t.kind,
                            name = c.getString(name) ?: "media",
                            size = c.getLong(size),
                            relPath = c.getString(rel) ?: "",
                            dateTakenMs = if (taken >= 0 && !c.isNull(taken)) c.getLong(taken) else null,
                            dateAddedMs = c.getLong(added) * 1000,
                            dateModifiedMs = c.getLong(modified) * 1000,
                            width = if (w >= 0) c.getInt(w) else 0,
                            height = if (h >= 0) c.getInt(h) else 0,
                            durationMs = if (dur >= 0 && !c.isNull(dur)) c.getLong(dur) else null,
                            mime = c.getString(mime),
                        )
                    }
                }
            }
        }
        return out
    }

    // ---- fingerprints, cached across visits ----

    private val cacheFile = File(context.filesDir, "cleanup-fingerprints.tsv")
    private var cache: HashMap<String, String>? = null

    private fun key(f: MediaFile, stage: DuplicateFinder.Stage) = "${f.id}:${f.size}:${f.dateModifiedMs}:${stage.ordinal}"

    @Synchronized
    private fun cacheMap(): HashMap<String, String> {
        cache?.let { return it }
        val m = HashMap<String, String>()
        runCatching {
            if (cacheFile.isFile) cacheFile.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        cache = m
        return m
    }

    /** A cached fingerprint is reused only while the file's id, size and modification time match. */
    fun fingerprint(f: MediaFile, stage: DuplicateFinder.Stage): String? {
        val k = key(f, stage)
        synchronized(this) { cacheMap()[k]?.let { return it } }
        val hash = runCatching { if (stage == DuplicateFinder.Stage.PARTIAL) partial(f) else full(f) }.getOrNull() ?: return null
        synchronized(this) { cacheMap()[k] = hash }
        return hash
    }

    /** Keeps only entries for files that still exist, so the cache stays bounded by the media on the device. */
    @Synchronized
    fun saveFingerprints(live: Collection<MediaFile>) {
        val m = cacheMap()
        val keep = live.flatMap { f -> DuplicateFinder.Stage.entries.map { key(f, it) } }.toHashSet()
        m.keys.retainAll(keep)
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.bufferedWriter().use { w -> for ((k, v) in m) w.write("$k\t$v\n") }
            tmp.renameTo(cacheFile)
        }
    }

    private fun partial(f: MediaFile): String? {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(f.size).array())
        context.contentResolver.openFileDescriptor(Uri.parse(f.uri), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val buf = ByteBuffer.allocate(WINDOW)
                fun readAt(pos: Long) {
                    buf.clear(); ch.position(pos)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    buf.flip(); md.update(buf)
                }
                readAt(0)
                if (f.size > WINDOW) readAt(maxOf(WINDOW.toLong(), f.size - WINDOW))
            }
        } ?: return null
        return md.digest().hex()
    }

    private fun full(f: MediaFile): String? {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(Uri.parse(f.uri))?.use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); total += n }
        } ?: return null
        // A file that changed size while being read is not the file MediaStore described.
        return if (total == f.size) md.digest().hex() else null
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // ---- look-alikes ----

    /** Each photo's CLIP vector (the still-image one, not video keyframes), normalized. */
    suspend fun photoVectors(ids: Set<Long>): List<Pair<Long, FloatArray>> =
        runCatching { db.embeddingDao().vectorsOfKind(EmbeddingKind.IMAGE_CLIP.ordinal) }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.source_ref == 0 && it.item_id in ids }
            .mapNotNull { row ->
                val v = Vecs.fromBytes(row.vec)
                if (v.isEmpty() || v.all { it == 0f }) null else row.item_id to Vecs.normalized(v)
            }
            .distinctBy { it.first }
            .toList()

    private companion object {
        const val WINDOW = 64 * 1024
    }
}

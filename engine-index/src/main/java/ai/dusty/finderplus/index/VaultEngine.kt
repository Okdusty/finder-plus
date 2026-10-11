package ai.dusty.finderplus.index

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ai.dusty.finderplus.db.FinderDatabase
import ai.dusty.finderplus.media.VaultCrypto
import ai.dusty.finderplus.media.VaultFormat

/**
 * The vault: media in folders the user marked private is encrypted out of shared storage and served
 * from this app's own index instead.
 *
 * Each file becomes an opaque `.fpv` blob ([VaultFormat] FPV2: AES-256-GCM, key wrapped by the Android
 * Keystore) under app-private storage. A `.nomedia` folder is not enough: it only hides files from
 * scanners, not from file managers. The database row keeps its id and all derived data; only
 * `content_uri` moves to the blob, and `original_path` records the way back.
 *
 * The order of operations is the data-loss policy:
 *  - Hide: encrypt to a temp file, verify it decrypts to the same hash, rename into place, update the
 *    row, and only then delete the original. A crash leaves either the original or a verified blob
 *    that the next run finishes moving.
 *  - Restore: decrypt to a temp file beside the target, check the length, rename, rescan, update the
 *    row, and only then delete the blob. An existing file is never overwritten; a folder that cannot
 *    be written falls back to `Download/finder+ restored/`.
 *  - Rescue: FPV2 blobs carry their original path, so blobs no row points at are restored too.
 *  - Rotate: the new key is saved before any file is rewritten and each file is swapped atomically,
 *    so every file opens under some key at all times.
 *
 * Restored files get new MediaStore ids; `rekeyItem` moves the row and its children so no AI work is
 * orphaned. MediaStore favorites and edit metadata do not survive the round trip.
 */
@Singleton
class VaultEngine @Inject constructor(
    private val db: FinderDatabase,
    private val policy: VaultPolicy,
) {

    data class Candidate(
        val itemId: Long,
        val contentUri: Uri,
        val path: String,
        val relPath: String,
        val sizeBytes: Long,
        val mime: String?,
    )

    data class Report(
        val kept: Int,
        val moved: Int,
        val failed: Int,
        val byBucket: Map<String, Int>,
        val dryRun: Boolean,
        /** Plain-language notes for the user: why something was refused, what needs attention. */
        val notes: List<String> = emptyList(),
        /** Files that came back from the vault into Downloads instead of their original folder. */
        val toDownloads: Int = 0,
        /** Blobs no database row knew about, recovered from the vault folder itself. */
        val rescued: Int = 0,
    )

    data class CheckReport(
        val verified: Int,
        val legacy: Int,
        val damaged: Int,
        val noKey: Int,
        val missing: Int,
        val orphans: Int,
        val repaired: Int,
        val needsUpgrade: Int,
        val problems: List<String>,
    ) {
        val healthy: Boolean get() = damaged == 0 && noKey == 0 && missing == 0
    }

    /**
     * App-private external storage: invisible to other apps and to MediaStore, kept across updates.
     * Uninstall deletes it, which is why restore must always be able to bring every file back.
     */
    fun vaultRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "vault")

    /** Every finished blob on disk. The disk is the source of truth; the database only indexes it. */
    fun blobFiles(context: Context): List<File> {
        val root = vaultRoot(context)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(VaultCrypto.EXT) }.toList()
    }

    fun hasBlobs(context: Context): Boolean = blobFiles(context).isNotEmpty()

    fun keyStatus(context: Context): VaultCrypto.KeyStatus {
        VaultCrypto.init(context)
        return VaultCrypto.keyStatus(hasBlobs(context))
    }

    /** A folder as the settings screen sees it: how many items, and what the policy says today. */
    data class Folder(val relPath: String, val count: Int, val decision: VaultPolicy.Decision, val hiddenCount: Int)

    /**
     * Every media folder with counts: visible items from MediaStore, hidden ones from our rows, so a
     * fully vaulted folder stays listed in settings.
     */
    suspend fun folders(context: Context): List<Folder> {
        val visible = HashMap<String, Int>()
        val (_, all) = candidates(context, usePolicy = false)
        for (c in all) visible.merge(c.relPath.trimEnd('/').ifEmpty { "(root)" }, 1, Int::plus)
        // Camera is a candidate-list exclusion, so count it separately — it must still be listable.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
        )?.use { c ->
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val rel = (c.getString(relCol) ?: "").trimEnd('/')
                if (rel.startsWith(CAMERA_PREFIX, ignoreCase = true)) visible.merge(rel, 1, Int::plus)
            }
        }

        val hidden = HashMap<String, Int>()
        for (row in db.mediaItemDao().vaulted()) {
            val rel = row.original_path
                ?.removePrefix(STORAGE_ROOT)
                ?.substringBeforeLast('/', "")
                ?.ifEmpty { "(root)" } ?: continue
            hidden.merge(rel, 1, Int::plus)
        }

        return (visible.keys + hidden.keys).map { rel ->
            Folder(rel, visible[rel] ?: 0, policy.decide(rel), hidden[rel] ?: 0)
        }.sortedByDescending { it.count + it.hiddenCount }
    }

    /**
     * Media the policy says should be hidden but that is still visible.
     *
     * @param only when non-blank, restricts to relative paths containing this substring, so a run
     *   can be tried on one folder first.
     * @param usePolicy false lists every non-camera item regardless of rules (the inventory view).
     */
    fun candidates(context: Context, only: String = "", usePolicy: Boolean = true): Pair<Int, List<Candidate>> {
        var kept = 0
        val out = ArrayList<Candidate>()
        val tables = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        for (table in tables) {
            context.contentResolver.query(table, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (c.moveToNext()) {
                    val rel = c.getString(relCol) ?: ""
                    if (rel.startsWith(CAMERA_PREFIX, ignoreCase = true) && policy.decide(rel) != VaultPolicy.Decision.HIDE) {
                        kept++; continue
                    }
                    if (only.isNotBlank() && !rel.contains(only, ignoreCase = true)) continue
                    if (usePolicy && policy.decide(rel) != VaultPolicy.Decision.HIDE) { kept++; continue }
                    val path = c.getString(dataCol) ?: continue
                    val id = c.getLong(idCol)
                    out += Candidate(
                        itemId = id,
                        contentUri = ContentUris.withAppendedId(table, id),
                        path = path,
                        relPath = rel,
                        sizeBytes = c.getLong(sizeCol),
                        mime = c.getString(mimeCol),
                    )
                }
            }
        }
        return kept to out
    }

    // =============================================================================================
    // Hide
    // =============================================================================================

    /** Encrypt every candidate into the vault. See the class doc for the order of operations. */
    suspend fun hide(
        context: Context,
        dryRun: Boolean,
        only: String = "",
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Report {
        val (kept, all) = candidates(context, only)
        val byBucket = all.groupingBy { it.relPath.trimEnd('/').ifEmpty { "(root)" } }.eachCount()
        if (dryRun) return Report(kept, all.size, 0, byBucket, dryRun = true)

        VaultCrypto.init(context)
        refusal(context)?.let { why ->
            Log.e(TAG, "REFUSED hide: $why")
            return Report(kept, 0, all.size, byBucket, dryRun = false, notes = listOf(why))
        }

        val root = vaultRoot(context)
        root.mkdirs()
        sweepTempFiles(root)

        var moved = 0; var failed = 0
        val notes = ArrayList<String>()
        val oldPaths = ArrayList<String>(all.size)
        val now = System.currentTimeMillis()
        for ((i, cand) in all.withIndex()) {
            // Cancellation lands between files, never between "row updated" and "original deleted".
            currentCoroutineContext().ensureActive()
            val src = File(cand.path)
            if (!src.exists()) { failed++; continue }

            // A run that died between "row points at blob" and "delete original" left both. Finish it
            // only when the blob holds these exact bytes: the file could be a new one reusing the name.
            if (finishInterruptedMove(cand, src)) {
                oldPaths += cand.path; moved++; continue
            }

            val dest = dedupe(File(root, cand.relPath + src.name + VaultCrypto.EXT))
            dest.parentFile?.mkdirs()
            val meta = VaultFormat.Meta(cand.path, src.name, cand.mime, src.lastModified(), now)
            if (!VaultCrypto.encryptFile(src, dest, meta)) {
                failed++
                if (notes.size < MAX_NOTES) notes += "Couldn't encrypt ${src.name} — it was left where it is"
                continue
            }

            // Row first, original second. Use Uri.fromFile, not string concatenation: a '#' or '?' in
            // the filename would parse as a fragment or query and truncate the path.
            try {
                db.mediaItemDao().vaultItem(cand.itemId, Uri.fromFile(dest).toString(), cand.path)
            } catch (c: CancellationException) {
                dest.delete(); throw c
            } catch (t: Throwable) {
                // Without the row nothing would know where the blob belongs: keep the original.
                Log.w(TAG, "row update failed for ${cand.path}: $t")
                dest.delete()
                failed++
                continue
            }
            if (!src.delete()) {
                // The original stays, so the blob must not: undo, so nothing exists twice.
                withContext(NonCancellable) {
                    runCatching { db.mediaItemDao().unvaultItem(cand.itemId, cand.contentUri.toString()) }
                }
                dest.delete()
                failed++
                if (notes.size < MAX_NOTES) notes += "Couldn't remove ${src.name} from the gallery — left visible"
                continue
            }
            Log.i(TAG, "vaulted ${cand.path}")
            oldPaths += cand.path
            moved++
            if (moved % 25 == 0) onProgress(i + 1, all.size)
        }

        // One batch rescan drops every stale gallery row (scanner sees the files are gone).
        if (oldPaths.isNotEmpty()) MediaScannerConnection.scanFile(context, oldPaths.toTypedArray(), null, null)
        onProgress(all.size, all.size)
        return Report(kept, moved, failed, byBucket, dryRun = false, notes = notes)
    }

    /** Why hiding must not run right now, in words for the user; null when it may. */
    fun refusal(context: Context): String? {
        VaultCrypto.init(context)
        if (keyStatus(context) == VaultCrypto.KeyStatus.LOCKED) {
            return "The vault is locked on this phone — import your recovery key first"
        }
        if (!VaultCrypto.isRecoveryKeySaved(context)) return "Save a recovery key first"
        return null
    }

    private suspend fun finishInterruptedMove(cand: Candidate, src: File): Boolean {
        val row = runCatching { db.mediaItemDao().vaultedFrom(cand.path) }.getOrNull() ?: return false
        val blob = Uri.parse(row.content_uri).path?.let(::File)?.takeIf { it.isFile } ?: return false
        val same = runCatching {
            VaultCrypto.plaintextSize(blob) == src.length() &&
                VaultCrypto.plaintextSha256(blob).contentEquals(sha256(src))
        }.getOrDefault(false)
        if (!same || !src.delete()) return false
        if (row.id != cand.itemId) runCatching { db.mediaItemDao().purge(cand.itemId) } // a second row for the same file
        Log.i(TAG, "finished interrupted move of ${cand.path}")
        return true
    }

    // =============================================================================================
    // Restore + rescue
    // =============================================================================================

    /**
     * Decrypt everything back to its original location, re-keying rows to their new MediaStore ids,
     * then restore blobs no row knows about.
     */
    suspend fun restore(context: Context, onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): Report {
        VaultCrypto.init(context)
        sweepTempFiles(vaultRoot(context))
        val rows = db.mediaItemDao().vaulted()
        val referenced = HashSet<String>()
        var moved = 0; var failed = 0; var toDownloads = 0; var rescued = 0
        val notes = ArrayList<String>()
        val totalGuess = maxOf(rows.size, blobFiles(context).size)

        for ((i, row) in rows.withIndex()) {
            currentCoroutineContext().ensureActive()
            val blob = Uri.parse(row.content_uri).path?.let(::File)?.takeIf { it.isFile }
                ?: row.original_path?.let { expectedBlobFor(context, it) }?.takeIf { it.isFile }
            if (blob == null) {
                failed++
                if (notes.size < MAX_NOTES) notes += "${row.display_name ?: "A file"} has no encrypted copy left to restore"
                continue
            }
            referenced += blob.absolutePath
            val target = row.original_path ?: VaultCrypto.readMeta(blob)?.originalPath ?: derivedOriginalPath(context, blob)
            val back = restoreBlob(context, blob, target, row.mime)
            if (back == null) {
                failed++
                if (notes.size < MAX_NOTES) notes += "${row.display_name ?: blob.name} couldn't be decrypted — it stays in the vault"
                continue
            }
            if (back.inDownloads) toDownloads++

            val newId = back.uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: -1L
            withContext(NonCancellable) {
                when {
                    back.uri == null -> db.mediaItemDao().unvaultItem(row.id, Uri.fromFile(File(back.path)).toString())
                    newId > 0 && newId != row.id -> {
                        val rekeyed = runCatching { db.mediaItemDao().rekeyItem(row.id, newId) }
                            .onFailure { Log.w(TAG, "rekey ${row.id}→$newId failed: $it") }.isSuccess
                        // If the scanner already made a row for the new id, keep ours un-vaulted: the
                        // next scan sees it as gone and converges, and nothing points at the vault.
                        db.mediaItemDao().unvaultItem(if (rekeyed) newId else row.id, back.uri.toString())
                        if (rekeyed) {
                            // The keyword index is keyed by item id outside the foreign-key graph.
                            db.ftsDao().deleteRow(row.id)
                            runCatching { ItemFinalizer(db).rebuildSearch(newId) }
                        }
                    }
                    else -> db.mediaItemDao().unvaultItem(row.id, back.uri.toString())
                }
            }
            blob.delete() // last: the plaintext is home and the row knows it
            moved++
            if (moved % 25 == 0) onProgress(i + 1, totalGuess)
        }

        for (blob in blobFiles(context)) {
            currentCoroutineContext().ensureActive()
            if (blob.absolutePath in referenced) continue
            val meta = VaultCrypto.readMeta(blob)
            val target = meta?.originalPath ?: derivedOriginalPath(context, blob)
            val back = restoreBlob(context, blob, target, meta?.mime)
            if (back == null) {
                failed++
                if (notes.size < MAX_NOTES) notes += "${blob.name.removeSuffix(VaultCrypto.EXT)} couldn't be decrypted — it stays in the vault"
                continue
            }
            if (back.inDownloads) toDownloads++
            blob.delete()
            moved++; rescued++
        }
        if (toDownloads > 0) notes += "$toDownloads file${if (toDownloads == 1) "" else "s"} went to Download/finder+ restored because the original folder couldn't be written"
        onProgress(totalGuess, totalGuess)
        return Report(0, moved, failed, emptyMap(), dryRun = false, notes = notes, toDownloads = toDownloads, rescued = rescued)
    }

    private class Restored(val path: String, val uri: Uri?, val inDownloads: Boolean)

    /** Decrypt [blob] to [targetPath] (or a free name beside it), falling back to Downloads. */
    private suspend fun restoreBlob(context: Context, blob: File, targetPath: String, mime: String?): Restored? {
        val size = runCatching { VaultCrypto.plaintextSize(blob) }.getOrNull() ?: return null
        val wanted = File(targetPath)
        val dest = freeName(wanted)
        val written = try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, ".${dest.name}.restoring")
            try {
                VaultCrypto.openDecrypting(blob).use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 16); out.fd.sync() }
                }
                if (tmp.length() != size) throw IOException("restored ${tmp.length()} of $size bytes")
                if (dest.exists() || !tmp.renameTo(dest)) throw IOException("could not move into place")
            } finally {
                tmp.delete()
            }
            true
        } catch (e: ai.dusty.finderplus.media.VaultIntegrityException) {
            Log.e(TAG, "restore ${blob.name}: damaged — $e")
            return null // the blob itself is bad; another destination will not help
        } catch (t: Throwable) {
            Log.w(TAG, "restore ${blob.name} to ${dest.path} failed: $t")
            false
        }
        if (written) return Restored(dest.absolutePath, scanAndWait(context, dest.absolutePath), inDownloads = false)
        return restoreToDownloads(context, blob, wanted.name, mime, size)
    }

    /** Needs no storage permission at all on Android 10+, which is what makes it a safe fallback. */
    private fun restoreToDownloads(context: Context, blob: File, name: String, mime: String?, size: Long): Restored? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/finder+ restored")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return try {
            var copied = 0L
            resolver.openOutputStream(uri)!!.use { out ->
                VaultCrypto.openDecrypting(blob).use { input -> copied = input.copyTo(out, 1 shl 16) }
            }
            if (copied != size) throw IOException("restored $copied of $size bytes")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Restored(uri.toString(), uri, inDownloads = true)
        } catch (t: Throwable) {
            Log.w(TAG, "restore ${blob.name} to Downloads failed: $t")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private suspend fun scanAndWait(context: Context, path: String): Uri? =
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                    if (cont.isActive) cont.resume(uri)
                }
            }
        }

    // =============================================================================================
    // Key rotation, format upgrade, health check
    // =============================================================================================

    /**
     * Rewrite every file not yet in FPV2 under the active key: FPV1 files (unauthenticated AES-CTR),
     * and after a rotation, files under an older key. Each file is replaced by an atomic rename and
     * old keys are retired only once no file needs them.
     *
     * Rotation itself is not done here: a new key must be covered by a saved, verified recovery file
     * before it becomes active, which needs the user's passphrase (see VaultCrypto.beginRotation).
     */
    suspend fun upgrade(context: Context, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Report {
        VaultCrypto.init(context)
        if (keyStatus(context) == VaultCrypto.KeyStatus.LOCKED) {
            return Report(0, 0, 0, emptyMap(), dryRun = false, notes = listOf("The vault is locked — import your recovery key first"))
        }
        return rewriteAll(context, onProgress)
    }

    private suspend fun rewriteAll(context: Context, onProgress: suspend (Int, Int) -> Unit): Report {
        val root = vaultRoot(context)
        sweepTempFiles(root)
        val rowsByBlob = db.mediaItemDao().vaulted().associateBy { Uri.parse(it.content_uri).path }
        val todo = blobFiles(context).filter { VaultCrypto.needsRewrite(it) }
        var done = 0; var failed = 0
        val notes = ArrayList<String>()
        val now = System.currentTimeMillis()
        for ((i, blob) in todo.withIndex()) {
            currentCoroutineContext().ensureActive()
            // FPV1 blobs have no meta record; give the new one where the file came from.
            val row = rowsByBlob[blob.absolutePath]
            val meta = VaultFormat.Meta(
                row?.original_path ?: derivedOriginalPath(context, blob), row?.display_name, row?.mime,
                row?.date_modified ?: 0, now,
            )
            val tmp = File(blob.parentFile, blob.name + ".rotating")
            if (!VaultCrypto.rewrite(blob, tmp, meta)) {
                failed++
                if (notes.size < MAX_NOTES) notes += "${row?.display_name ?: blob.name} couldn't be re-encrypted — it still opens with the old key"
                continue
            }
            if (!tmp.renameTo(blob)) { tmp.delete(); failed++; continue }
            done++
            if (done % 25 == 0) onProgress(i + 1, todo.size)
        }
        // Keys stay until no file needs them; with failures, the old key is simply kept. Blob paths the
        // index holds count too, in case any live outside the folder walked here.
        val indexed = db.mediaItemDao().vaulted().mapNotNull { Uri.parse(it.content_uri).path?.let(::File) }.filter { it.isFile }
        VaultCrypto.retireUnusedKeys((blobFiles(context) + indexed).distinctBy { it.absolutePath })
        onProgress(todo.size, todo.size)
        Log.i(TAG, "rewrote $done files, $failed failed")
        return Report(0, done, failed, emptyMap(), dryRun = false, notes = notes)
    }

    /**
     * Full health check: every blob decrypted end to end (FPV2 authenticates every chunk), rows
     * repaired where the blob merely moved, and blobs no row knows about counted so Restore can
     * bring them home. Reads the whole vault — run it in a worker.
     */
    suspend fun check(context: Context, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): CheckReport {
        VaultCrypto.init(context)
        val rows = db.mediaItemDao().vaulted()
        var verified = 0; var legacy = 0; var damaged = 0; var noKey = 0; var missing = 0; var repaired = 0
        var needsUpgrade = 0
        val problems = ArrayList<String>()
        val referenced = HashSet<String>()
        for ((i, row) in rows.withIndex()) {
            var f = File(Uri.parse(row.content_uri).path ?: "")
            if (!f.isFile) {
                // A row whose URI was built without encoding points at a truncated path ('#' in the
                // filename became a fragment). The blob is fine; rewrite the row.
                val origin = row.original_path
                val expected = origin?.let { expectedBlobFor(context, it) }
                if (origin != null && expected != null && expected.isFile) {
                    db.mediaItemDao().vaultItem(row.id, Uri.fromFile(expected).toString(), origin)
                    f = expected
                    repaired++
                } else {
                    missing++
                    if (problems.size < MAX_NOTES) problems += "Missing: ${row.display_name}"
                    continue
                }
            }
            referenced += f.absolutePath
            when (VaultCrypto.verify(f)) {
                VaultCrypto.Verdict.OK -> verified++
                VaultCrypto.Verdict.OK_LEGACY -> legacy++
                VaultCrypto.Verdict.NO_KEY -> { noKey++; if (problems.size < MAX_NOTES) problems += "No key: ${row.display_name}" }
                VaultCrypto.Verdict.DAMAGED -> { damaged++; if (problems.size < MAX_NOTES) problems += "Damaged: ${row.display_name}" }
            }
            if (VaultCrypto.needsRewrite(f)) needsUpgrade++
            if ((i + 1) % 25 == 0) onProgress(i + 1, rows.size)
        }
        val orphans = blobFiles(context).count { it.absolutePath !in referenced }
        onProgress(rows.size, rows.size)
        return CheckReport(verified, legacy, damaged, noKey, missing, orphans, repaired, needsUpgrade, problems)
    }

    // =============================================================================================
    // Paths
    // =============================================================================================

    /**
     * Where a vaulted file's blob belongs, derived from the path it came from. Used to repair rows
     * whose stored URI cannot be parsed back into a working path.
     */
    fun expectedBlobFor(context: Context, originalPath: String): File {
        val rel = originalPath.removePrefix(STORAGE_ROOT)
        return File(vaultRoot(context), rel + VaultCrypto.EXT)
    }

    /** The inverse of [expectedBlobFor], for FPV1 blobs that carry no record of their origin. */
    private fun derivedOriginalPath(context: Context, blob: File): String {
        val rel = blob.absolutePath.removePrefix(vaultRoot(context).absolutePath + "/").removeSuffix(VaultCrypto.EXT)
        return STORAGE_ROOT + rel
    }

    /** Same-name collision inside the vault gets a numeric suffix, never an overwrite. */
    private fun dedupe(f: File): File {
        if (!f.exists()) return f
        val name = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val cand = File(f.parentFile, "$name-$n$ext")
            if (!cand.exists()) return cand
            n++
        }
    }

    /** "IMG_1.jpg" → "IMG_1 (restored).jpg" → "IMG_1 (restored 2).jpg": a restore never overwrites. */
    private fun freeName(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val cand = File(f.parentFile, if (n == 1) "$base (restored)$ext" else "$base (restored $n)$ext")
            if (!cand.exists()) return cand
            n++
        }
    }

    /**
     * Temp files are only ever an unfinished copy with the original or previous blob still intact
     * beside it, so leftovers from a crash are safe to delete.
     */
    private fun sweepTempFiles(root: File) {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith("${VaultCrypto.EXT}.part") || it.name.endsWith(".rotating") || it.name.endsWith(".rotating.part")) }
            .forEach { Log.i(TAG, "removing unfinished ${it.name}"); it.delete() }
    }

    private fun sha256(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    private companion object {
        const val CAMERA_PREFIX = "DCIM/Camera"
        const val STORAGE_ROOT = "/storage/emulated/0/"
        const val TAG = "finderVault"
        const val MAX_NOTES = 8
    }
}

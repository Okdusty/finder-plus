package ai.dusty.finderplus.index

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import ai.dusty.finderplus.db.FinderDatabase

/**
 * The vault: every photo and video that did not come out of the camera is moved out of the world the
 * gallery can see, while this app keeps serving it — search, thumbnails, copy, open — from its own
 * index.
 *
 * ### Mechanism: encrypted, not merely hidden
 *
 * A `.nomedia` dot-folder only asks scanners politely — any file manager still shows the pictures.
 * So each file is **encrypted** (AES-256-CTR, key wrapped by the Android Keystore) into an opaque
 * `.fpv` blob under app-private storage, `Android/data/<pkg>/files/vault/`, and the plaintext is
 * removed only after the ciphertext verifies. What remains on disk is unreadable to every gallery,
 * file manager, and app but this one — including a phone in someone else's hands.
 *
 * The database row keeps its id and every derived artifact (tags, captions, embeddings, votes);
 * only `content_uri` flips to the `file://` blob and `original_path` records the way back.
 *
 * ### What counts as "camera"
 *
 * `RELATIVE_PATH` beginning with `DCIM/Camera` — where every camera app on this device writes.
 * Everything else (Download, Screenshots, WhatsApp, Telegram, Reddit, …) is vault material.
 * Audio is left alone: it was never in the gallery to begin with.
 *
 * ### Restore
 *
 * [restore] renames everything back and rescans, at which point MediaStore assigns **new** ids; the
 * DB row and all children follow via `rekeyItem` so no AI work is orphaned. The one thing not
 * preserved across a vault round-trip is MediaStore favorites/edits metadata, which lived in the
 * gallery row this operation deletes — stated openly rather than discovered later.
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
    )

    data class Report(
        val kept: Int,
        val moved: Int,
        val failed: Int,
        val byBucket: Map<String, Int>,
        val dryRun: Boolean,
    )

    /**
     * App-private external storage: no other app can list it without root, it is excluded from
     * MediaStore by construction, and it survives app updates. (Uninstall removes it — the restore
     * path exists precisely so the vault is never the only copy of anything the user still wants.)
     */
    fun vaultRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "vault")

    /** A folder as the settings screen sees it: how many items, and what the policy says today. */
    data class Folder(val relPath: String, val count: Int, val decision: VaultPolicy.Decision, val hiddenCount: Int)

    /**
     * Every folder that holds gallery media, with counts — visible items from MediaStore, hidden
     * ones from our own rows, so a fully-vaulted folder does not simply vanish from the settings
     * screen the moment it is hidden.
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
                ?.removePrefix("/storage/emulated/0/")
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
     * @param only when non-blank, restricts to relative paths containing this substring — how a run
     *   is rehearsed on one folder before it is trusted with the whole gallery.
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
        )
        for (table in tables) {
            context.contentResolver.query(table, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
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
                    )
                }
            }
        }
        return kept to out
    }

    /**
     * Hide everything non-camera. Per file: rename into the vault, verify, repoint the DB row.
     * The gallery rows are dropped afterwards by rescanning the now-empty old paths.
     */
    suspend fun hide(
        context: Context,
        dryRun: Boolean,
        only: String = "",
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Report {
        val (kept, all) = candidates(context, only)
        val byBucket = all.groupingBy { it.relPath.trimEnd('/').ifEmpty { "(root)" } }.eachCount()
        if (dryRun) return Report(kept, all.size, 0, byBucket, dryRun = true)
        if (!ai.dusty.finderplus.media.VaultCrypto.isRecoveryKeySaved(context)) {
            android.util.Log.e("finderVault", "REFUSED hide: recovery key not saved")
            return Report(kept, 0, all.size, byBucket, dryRun = false)
        }

        ai.dusty.finderplus.media.VaultCrypto.init(context)
        val root = vaultRoot(context)
        root.mkdirs()

        var moved = 0; var failed = 0
        val oldPaths = ArrayList<String>(all.size)
        for ((i, cand) in all.withIndex()) {
            val src = File(cand.path)
            if (!src.exists()) { failed++; continue }
            val dest = File(root, cand.relPath + src.name + ai.dusty.finderplus.media.VaultCrypto.EXT).let(::dedupe)
            dest.parentFile?.mkdirs()

            // Encrypt first, verify, and only then remove the plaintext — an interrupted run can
            // leave a spare ciphertext (harmless, overwritten next time) but never a lost file.
            if (!ai.dusty.finderplus.media.VaultCrypto.encryptInto(src, dest)) { failed++; continue }
            if (!src.delete()) { dest.delete(); failed++; continue }

            // The DB row may not exist (file never indexed) — the move still hides it; the next scan
            // will simply never see it. When the row exists, everything derived survives untouched.
            // Uri.fromFile, not string concatenation: a filename containing '#' or '?' would
            // otherwise parse as a fragment/query and truncate the path — measured on three real
            // files ("... #humor #lmao ...") that became unreachable and unrestorable.
            runCatching { db.mediaItemDao().vaultItem(cand.itemId, Uri.fromFile(dest).toString(), cand.path) }
            android.util.Log.i("finderVault", "vaulted ${cand.path}")
            oldPaths += cand.path
            moved++
            if (moved % 50 == 0) onProgress(i + 1, all.size)
        }

        // One batch rescan drops every stale gallery row (scanner sees the files are gone).
        if (oldPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, oldPaths.toTypedArray(), null, null)
        }
        return Report(kept, moved, failed, byBucket, dryRun = false)
    }

    /** Decrypt everything home again and rejoin the gallery, re-keying rows to their new MediaStore ids. */
    suspend fun restore(context: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Report {
        val rows = db.mediaItemDao().vaulted()
        var moved = 0; var failed = 0
        for ((i, row) in rows.withIndex()) {
            ai.dusty.finderplus.media.VaultCrypto.init(context)
            val src = File(Uri.parse(row.content_uri).path ?: continue)
            val destPath = row.original_path ?: continue
            val dest = File(destPath)
            dest.parentFile?.mkdirs()
            if (!src.exists()) { failed++; continue }
            val restored = runCatching {
                ai.dusty.finderplus.media.VaultCrypto.openDecrypting(src).use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
                }
                dest.length() == ai.dusty.finderplus.media.VaultCrypto.plaintextSize(src)
            }.getOrDefault(false)
            if (!restored) { dest.delete(); failed++; continue }
            src.delete()

            val scannedUri = scanAndWait(context, destPath)
            if (scannedUri != null) {
                val newId = runCatching { ContentUris.parseId(scannedUri) }.getOrDefault(-1L)
                if (newId > 0 && newId != row.id) {
                    runCatching { db.mediaItemDao().rekeyItem(row.id, newId) }
                    db.mediaItemDao().unvaultItem(newId, scannedUri.toString())
                } else {
                    db.mediaItemDao().unvaultItem(row.id, scannedUri.toString())
                }
            } else {
                // Scanner gave nothing back; the file is home and visible, the row keeps file access.
                db.mediaItemDao().unvaultItem(row.id, Uri.fromFile(dest).toString())
            }
            moved++
            if (moved % 25 == 0) onProgress(i + 1, rows.size)
        }
        return Report(0, moved, failed, emptyMap(), dryRun = false)
    }

    private suspend fun scanAndWait(context: Context, path: String): Uri? =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }

    /**
     * Re-encrypt the whole vault under a brand-new data key.
     *
     * The recovery passphrase protects the *key*, and there is only ever one key, so changing the
     * passphrase already covers every file ever hidden. Rotation is the stronger operation: it
     * replaces the key itself, so a previously exported recovery file stops working — which is what
     * you want after a key may have been seen by someone else.
     *
     * Every file is rewritten to a temporary blob and swapped in only after it verifies, one at a
     * time, so an interruption leaves each file readable under exactly one of the two keys rather
     * than a vault half-readable under each. The new key is installed **last**, after every file has
     * been rewritten, because that is the point of no return.
     */
    suspend fun rotateKey(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Report {
        ai.dusty.finderplus.media.VaultCrypto.init(context)
        val rows = db.mediaItemDao().vaulted()
        val oldKey = ai.dusty.finderplus.media.VaultCrypto.currentKeyBytes()
        val newKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

        var done = 0; var failed = 0; var absent = 0
        val staged = ArrayList<Pair<File, File>>(rows.size) // temp -> final
        for ((i, row) in rows.withIndex()) {
            val blob = File(Uri.parse(row.content_uri).path ?: continue)
            // A row whose blob is already gone is a pre-existing inconsistency, not a rotation
            // failure. Treating it as one made rotation permanently impossible: a single stale row
            // would abort every attempt forever, which is the wrong thing to be strict about.
            if (!blob.exists()) { absent++; continue }
            val tmp = File(blob.parentFile, blob.name + ".rotating")
            val ok = ai.dusty.finderplus.media.VaultCrypto.reEncrypt(blob, tmp, oldKey, newKey)
            if (!ok) { tmp.delete(); failed++; continue }
            staged += tmp to blob
            done++
            if (done % 50 == 0) onProgress(i + 1, rows.size)
        }

        if (failed > 0) {
            // Never a partial rotation: if a file that exists could not be rewritten, discard the
            // whole batch and leave the vault exactly as it was, readable under the existing key.
            staged.forEach { (tmp, _) -> tmp.delete() }
            android.util.Log.w("finderVault", "rotation aborted: $failed of ${rows.size} files could not be rewritten")
            return Report(0, 0, failed, emptyMap(), dryRun = false)
        }

        staged.forEach { (tmp, final) -> tmp.renameTo(final) }
        ai.dusty.finderplus.media.VaultCrypto.installKey(newKey)
        android.util.Log.i("finderVault", "rotated vault key across $done files ($absent rows had no blob)")
        return Report(absent, done, 0, emptyMap(), dryRun = false)
    }

    /**
     * Where a vaulted file's blob belongs, derived from the path it came from. Used to repair rows
     * whose stored URI cannot be parsed back into a working path.
     */
    fun expectedBlobFor(context: Context, originalPath: String): File {
        val rel = originalPath.removePrefix("/storage/emulated/0/")
        return File(vaultRoot(context), rel + ai.dusty.finderplus.media.VaultCrypto.EXT)
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

    private companion object {
        const val CAMERA_PREFIX = "DCIM/Camera"
    }
}

package ai.dusty.finderplus.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs [VaultEngine] off the main thread with a progress notification.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_HIDE --ez dry true -p <pkg>
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_HIDE -p <pkg>
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_RESTORE -p <pkg>
 *
 * Requires All-Files access (`MANAGE_EXTERNAL_STORAGE`), grantable without UI via
 * `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`. Without it the worker logs and exits
 * rather than half-moving a gallery.
 */
@HiltWorker
class VaultWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: VaultEngine,
    private val db: ai.dusty.finderplus.db.FinderDatabase,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIF_ID, notification("Working…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(text: String): Notification =
        Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID)
            .setContentTitle("Organizing gallery")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }

        // Restore never needs all-files access (the vault is ours and the destinations are ours to
        // write via MediaStore rescan), but hiding does — it deletes originals from shared storage.
        if (!inputData.getBoolean(KEY_RESTORE, false) && !Environment.isExternalStorageManager()) {
            log("All-Files access not granted — refusing to move anything. Grant with: adb shell appops set ${applicationContext.packageName} MANAGE_EXTERNAL_STORAGE allow")
            return Result.success()
        }

        val restore = inputData.getBoolean(KEY_RESTORE, false)
        val dry = inputData.getBoolean(KEY_DRY, false)

        // Self-test before any bulk run touches the user's gallery: encrypt a synthetic file,
        // decrypt it sequentially AND seek into it at odd offsets, and compare every byte. If the
        // envelope is wrong on this device (Keystore quirk, provider difference), it must be found
        // here — on throwaway bytes — not after four thousand photos are ciphertext.
        if (inputData.getBoolean(KEY_SELFTEST, false)) {
            selfTest()
            return Result.success()
        }

        // Integrity sweep over the real vault: decrypt the head of every blob and confirm it is
        // still the media it claims to be. Cheap (a few hundred bytes each) and it answers the only
        // question that matters after a bulk move — "is everything still recoverable?" — with
        // evidence rather than optimism.
        if (inputData.getBoolean(KEY_VERIFY, false)) {
            verifyVault()
            return Result.success()
        }

        if (inputData.getBoolean(KEY_ROTATE, false)) {
            val r = engine.rotateKey(applicationContext) { d, t -> log("rotate progress $d/$t") }
            log(
                if (r.failed > 0) "ROTATE ABORTED: ${r.failed} files could not be rewritten; vault unchanged"
                else "ROTATE OK: ${r.moved} files re-encrypted under a new key"
            )
            return Result.success()
        }

        if (!restore && !dry &&
            !ai.dusty.finderplus.media.VaultCrypto.isRecoveryKeySaved(applicationContext)
        ) {
            log("REFUSED hide: save a recovery key first — uninstall would destroy hidden files")
            return Result.success()
        }

        val report = if (restore) {
            log("restore starting from ${engine.vaultRoot(applicationContext)}")
            engine.restore(applicationContext) { done, total -> log("restore progress $done/$total") }
        } else {
            engine.hide(applicationContext, dryRun = dry, only = inputData.getString(KEY_ONLY) ?: "") { done, total ->
                log("hide progress $done/$total")
            }
        }

        if (report.dryRun) {
            log("DRY RUN — camera items kept visible: ${report.kept}; would hide: ${report.moved}")
            report.byBucket.entries.sortedByDescending { it.value }.forEach { (bucket, n) ->
                log("  would hide $n from $bucket")
            }
        } else {
            log("done: kept ${report.kept} camera items visible, moved ${report.moved}, failed ${report.failed}")
        }
        return Result.success()
    }

    private fun selfTest() {
        ai.dusty.finderplus.media.VaultCrypto.init(applicationContext)
        val dir = java.io.File(applicationContext.cacheDir, "vault-selftest").apply { mkdirs() }
        val plain = java.io.File(dir, "plain.bin")
        val blob = java.io.File(dir, "blob.fpv")
        try {
            // 3 MB with a non-repeating pattern: large enough to cross many CTR blocks and to make
            // an off-by-one counter produce visibly wrong bytes rather than a lucky match.
            val src = ByteArray(3 * 1024 * 1024) { ((it * 31 + it / 977) % 251).toByte() }
            plain.writeBytes(src)

            if (!ai.dusty.finderplus.media.VaultCrypto.encryptInto(plain, blob)) {
                log("SELFTEST FAILED: encryption did not verify"); return
            }
            if (readBytes(blob, 4).contentEquals(src.copyOf(4))) {
                log("SELFTEST FAILED: ciphertext starts with plaintext"); return
            }
            val roundTrip = ai.dusty.finderplus.media.VaultCrypto.openDecrypting(blob).use { it.readBytes() }
            if (!roundTrip.contentEquals(src)) {
                log("SELFTEST FAILED: sequential decrypt mismatch (${roundTrip.size} vs ${src.size})"); return
            }
            if (ai.dusty.finderplus.media.VaultCrypto.plaintextSize(blob) != src.size.toLong()) {
                log("SELFTEST FAILED: size header wrong"); return
            }

            // Random access at deliberately unaligned offsets — the seek path thumbnails depend on.
            val ds = ai.dusty.finderplus.media.VaultCrypto.dataSource(blob)
            for (pos in listOf(0L, 1L, 15L, 16L, 4095L, 65_537L, src.size - 100L)) {
                val n = minOf(1000, (src.size - pos).toInt())
                val buf = ByteArray(n)
                val read = ds.readAt(pos, buf, 0, n)
                val want = src.copyOfRange(pos.toInt(), pos.toInt() + n)
                if (read != n || !buf.contentEquals(want)) {
                    log("SELFTEST FAILED: seek at $pos returned $read bytes, content mismatch"); ds.close(); return
                }
            }
            ds.close()
            log("SELFTEST PASSED: ${src.size} bytes encrypt→decrypt byte-identical, 7 seek offsets exact, ciphertext opaque")
        } catch (t: Throwable) {
            log("SELFTEST FAILED with exception: ${t}")
        } finally {
            plain.delete(); blob.delete(); dir.delete()
        }
    }

    private fun readBytes(f: java.io.File, n: Int): ByteArray = f.inputStream().use { it.readNBytes(n) }

    private suspend fun verifyVault() {
        ai.dusty.finderplus.media.VaultCrypto.init(applicationContext)
        val rows = db.mediaItemDao().vaulted()
        var ok = 0; var missing = 0; var bad = 0; var unknown = 0; var repaired = 0; var purged = 0
        val badNames = ArrayList<String>()
        for (row in rows) {
            var f = java.io.File(android.net.Uri.parse(row.content_uri).path ?: "")
            if (!f.exists()) {
                // Self-repair: a row written before URIs were properly encoded points at a truncated
                // path (a '#' in the filename became a fragment marker). The blob itself is fine —
                // recompute where it must be and rewrite the row with an encoded URI.
                val origin = row.original_path
                val expected = origin?.let { engine.expectedBlobFor(applicationContext, it) }
                if (origin != null && expected != null && expected.exists()) {
                    db.mediaItemDao().vaultItem(row.id, android.net.Uri.fromFile(expected).toString(), origin)
                    f = expected
                    repaired++
                } else {
                    missing++; badNames += "MISSING ${row.display_name}"
                    // A row whose blob is gone AND whose original never came back describes media
                    // that no longer exists anywhere. Only purged when explicitly asked: deleting
                    // index rows on a hunch is how a transient read error becomes data loss.
                    if (inputData.getBoolean(KEY_PURGE_MISSING, false) &&
                        origin != null && !java.io.File(origin).exists()
                    ) {
                        db.mediaItemDao().purge(row.id)
                        purged++
                    }
                    continue
                }
            }
            val head = runCatching {
                ai.dusty.finderplus.media.VaultCrypto.openDecrypting(f).use { it.readNBytes(16) }
            }.getOrNull()
            val size = runCatching { ai.dusty.finderplus.media.VaultCrypto.plaintextSize(f) }.getOrDefault(0L)
            when {
                head == null || size <= 0L -> { bad++; badNames += "UNREADABLE ${row.display_name}" }
                looksLikeMedia(head) -> ok++
                // Not every container starts with a signature we enumerate; a decodable header that
                // simply is not in the table is reported separately rather than called corrupt.
                else -> unknown++
            }
        }
        log("VERIFY: $ok verified, $unknown unrecognized-but-readable, $bad unreadable, $missing missing, $repaired repaired, $purged dead rows purged (of ${rows.size})")
        badNames.take(10).forEach { log("  $it") }
        verifyHandOff(rows.firstOrNull())
    }

    /**
     * Proves the path that copy and open depend on: decrypt a real vaulted file into the staging
     * cache and mint the FileProvider URI another app would receive. This is where hiding the
     * gallery could quietly become "the user can no longer get at their own files" — so it is
     * checked with a real file rather than assumed from the config.
     */
    private fun verifyHandOff(row: ai.dusty.finderplus.db.entity.MediaItemEntity?) {
        if (row == null) { log("HANDOFF: nothing vaulted to check"); return }
        runCatching {
            val blob = java.io.File(android.net.Uri.parse(row.content_uri).path!!)
            val dir = java.io.File(applicationContext.cacheDir, "vault-open").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val staged = java.io.File(dir, (row.display_name ?: "media").replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(96))
            ai.dusty.finderplus.media.VaultCrypto.openDecrypting(blob).use { input ->
                staged.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
            val head = staged.inputStream().use { it.readNBytes(16) }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                applicationContext, "${applicationContext.packageName}.clips", staged,
            )
            val sane = looksLikeMedia(head) && staged.length() == ai.dusty.finderplus.media.VaultCrypto.plaintextSize(blob)
            log(if (sane) "HANDOFF OK: ${staged.length()} bytes decrypted, shared as $uri"
                else "HANDOFF FAILED: staged ${staged.length()} bytes, header not media")
            dir.listFiles()?.forEach { it.delete() }
        }.onFailure { log("HANDOFF FAILED: $it") }
    }

    /** Magic numbers for the containers a phone gallery actually holds. */
    private fun looksLikeMedia(h: ByteArray): Boolean {
        fun at(i: Int) = h.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true                       // JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> true                       // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true                       // GIF
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> true      // MP4/MOV ftyp
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> true      // RIFF (WEBP/AVI)
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> true      // Matroska/WEBM
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x00 -> true                       // box-prefixed mp4
            else -> false
        }
    }

    private fun log(msg: String) = android.util.Log.i(TAG, msg)

    companion object {
        private const val TAG = "finderVault"
        private const val NOTIF_ID = 4211
        const val KEY_DRY = "dry"
        const val KEY_RESTORE = "restore"
        const val KEY_SELFTEST = "selftest"
        const val KEY_ONLY = "only"
        const val KEY_VERIFY = "verify"
        const val KEY_ROTATE = "rotate"
        const val KEY_PURGE_MISSING = "purgeMissing"

        fun enqueue(
            context: Context,
            dry: Boolean = false,
            restore: Boolean = false,
            selfTest: Boolean = false,
            only: String = "",
            verify: Boolean = false,
            rotate: Boolean = false,
            purgeMissing: Boolean = false,
        ) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "finder-vault", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VaultWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(KEY_DRY, dry)
                            .putBoolean(KEY_RESTORE, restore)
                            .putBoolean(KEY_SELFTEST, selfTest)
                            .putString(KEY_ONLY, only)
                            .putBoolean(KEY_VERIFY, verify)
                            .putBoolean(KEY_ROTATE, rotate)
                            .putBoolean(KEY_PURGE_MISSING, purgeMissing)
                            .build()
                    )
                    .build(),
            )
        }
    }
}

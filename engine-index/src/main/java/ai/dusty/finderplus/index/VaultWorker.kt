package ai.dusty.finderplus.index

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.dusty.finderplus.media.VaultCrypto
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs [VaultEngine] off the main thread with a progress notification, and leaves a plain-language
 * [VaultReport] behind for the Private folders screen.
 *
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_HIDE --ez dry true -p <pkg>
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_HIDE -p <pkg>
 *   adb shell am broadcast -a ai.dusty.finderplus.action.VAULT_RESTORE -p <pkg>
 *
 * Hiding requires All-Files access (`MANAGE_EXTERNAL_STORAGE`); without it the worker refuses rather
 * than move only some files. Restore does not, since it can fall back to Downloads.
 */
@HiltWorker
class VaultWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: VaultEngine,
) : CoroutineWorker(appContext, params) {

    private val op: Op get() = runCatching { Op.valueOf(inputData.getString(KEY_OP) ?: "") }.getOrDefault(Op.HIDE)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIF_ID, notification(op.working, 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(text: String, done: Int, total: Int): Notification =
        Notification.Builder(applicationContext, IndexWorker.CHANNEL_ID.also { IndexWorker.ensureChannel(applicationContext) })
            .setContentTitle("Private folders")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()

    /** Best effort — but a cancellation must propagate, never be swallowed as a failed update. */
    private suspend fun progress(done: Int, total: Int) {
        try {
            setProgress(workDataOf(KEY_OP to op.name, KEY_DONE to done, KEY_TOTAL to total))
            setForeground(
                ForegroundInfo(NOTIF_ID, notification("${op.working} $done of $total", done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        VaultCrypto.init(applicationContext)
        val ctx = applicationContext
        val report: VaultReport = when (op) {
            Op.SELFTEST -> selfTest()
            Op.CHECK -> {
                val r = engine.check(ctx) { d, t -> progress(d, t) }
                VaultReport(
                    op = op, ok = r.healthy,
                    summary = if (r.healthy) "All ${r.verified + r.legacy} hidden files open correctly"
                    else "${r.damaged + r.missing + r.noKey} of ${r.verified + r.legacy + r.damaged + r.missing + r.noKey} hidden files need attention",
                    details = buildList {
                        if (r.legacy > 0) add("${r.legacy} use the older encryption format — Upgrade to add tamper detection")
                        if (r.repaired > 0) add("Repaired ${r.repaired} index entries")
                        if (r.orphans > 0) add("${r.orphans} encrypted files aren't in the index — Restore all brings them back too")
                        if (r.noKey > 0) add("${r.noKey} files need a key this phone doesn't have — import the recovery key they were saved with")
                        addAll(r.problems)
                    },
                )
            }
            // ROTATE only names the operation in the UI; the key itself is switched there, after its recovery file is verified.
            Op.ROTATE, Op.REWRITE -> engine.upgrade(ctx) { d, t -> progress(d, t) }.toReport(
                okLine = { "Re-encrypted ${it.moved} file${if (it.moved == 1) "" else "s"} under the new key" },
            )
            Op.UPGRADE -> engine.upgrade(ctx) { d, t -> progress(d, t) }.toReport(
                okLine = { if (it.moved == 0) "Everything already uses the current format" else "Upgraded ${it.moved} files to tamper-proof encryption" },
            )
            Op.RESTORE -> engine.restore(ctx) { d, t -> progress(d, t) }.toReport(
                okLine = { r ->
                    buildString {
                        append(if (r.moved == 0) "Nothing to restore" else "Restored ${r.moved} file${if (r.moved == 1) "" else "s"} to your gallery")
                        if (r.rescued > 0) append(" (${r.rescued} recovered from outside the index)")
                    }
                },
            )
            Op.HIDE, Op.DRY_RUN -> {
                if (op == Op.HIDE && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                    VaultReport(op, ok = false, summary = "Needs all-files access before anything can be hidden", details = emptyList())
                } else {
                    val r = engine.hide(ctx, dryRun = op == Op.DRY_RUN, only = inputData.getString(KEY_ONLY) ?: "") { d, t -> progress(d, t) }
                    if (r.dryRun) {
                        VaultReport(op, ok = true, summary = "Would hide ${r.moved} files, keep ${r.kept} visible",
                            details = r.byBucket.entries.sortedByDescending { it.value }.map { (b, n) -> "$n from $b" })
                    } else r.toReport(okLine = { "Hid ${it.moved} file${if (it.moved == 1) "" else "s"}" })
                }
            }
        }
        report.save(ctx)
        android.util.Log.i(TAG, "${op.name}: ${report.summary}" + report.details.joinToString("") { "\n  $it" })
        return Result.success()
    }

    private fun VaultEngine.Report.toReport(okLine: (VaultEngine.Report) -> String): VaultReport {
        val ok = failed == 0 && notes.none { it.startsWith("Save a recovery") || it.contains("locked") }
        val summary = when {
            moved == 0 && failed > 0 && notes.isNotEmpty() -> notes.first()
            failed > 0 -> okLine(this) + " · $failed couldn't be processed and were left untouched"
            else -> okLine(this)
        }
        return VaultReport(op, ok, summary, notes.filter { it != summary })
    }

    /**
     * Encrypt a synthetic file, read it back sequentially and at odd offsets, and prove tampering and
     * truncation are caught, so a device-specific crypto fault shows up on throwaway bytes before
     * any bulk run touches user files.
     */
    private fun selfTest(): VaultReport {
        val dir = java.io.File(applicationContext.cacheDir, "vault-selftest").apply { mkdirs() }
        val plain = java.io.File(dir, "plain.bin")
        val blob = java.io.File(dir, "blob.fpv")
        val checks = ArrayList<String>()
        return try {
            // 3 MB with a non-repeating pattern: crosses many chunks, so an off-by-one chunk index
            // produces visibly wrong bytes rather than a lucky match.
            val src = ByteArray(3 * 1024 * 1024 + 4321) { ((it * 31 + it / 977) % 251).toByte() }
            plain.writeBytes(src)
            blob.delete()

            check(VaultCrypto.encryptInto(plain, blob)) { "encryption did not verify" }
            check(!readBytes(blob, 64).toList().windowed(4).any { it == src.copyOf(4).toList() }) { "ciphertext leaks plaintext" }
            val roundTrip = VaultCrypto.openDecrypting(blob).use { it.readBytes() }
            check(roundTrip.contentEquals(src)) { "sequential decrypt mismatch" }
            checks += "round trip"

            val ds = VaultCrypto.dataSource(blob)
            for (pos in listOf(0L, 1L, 15L, 65_535L, 65_536L, 65_537L, src.size - 100L)) {
                val n = minOf(1000, (src.size - pos).toInt())
                val buf = ByteArray(n)
                check(ds.readAt(pos, buf, 0, n) == n && buf.contentEquals(src.copyOfRange(pos.toInt(), pos.toInt() + n))) {
                    "seek at $pos returned wrong bytes"
                }
            }
            ds.close()
            checks += "7 seeks"

            java.io.RandomAccessFile(blob, "rw").use { raf -> raf.seek(70_000); val b = raf.read(); raf.seek(70_000); raf.write(b xor 1) }
            val tamperCaught = runCatching { VaultCrypto.openDecrypting(blob).use { it.readBytes() } }.isFailure
            check(tamperCaught) { "a flipped bit went undetected" }
            checks += "tamper detected"

            java.io.RandomAccessFile(blob, "rw").use { it.setLength(it.length() - 70_000) }
            check(VaultCrypto.verify(blob) == VaultCrypto.Verdict.DAMAGED) { "truncation went undetected" }
            checks += "truncation detected"

            VaultReport(Op.SELFTEST, ok = true, summary = "Encryption self-test passed", details = checks)
        } catch (t: Throwable) {
            VaultReport(Op.SELFTEST, ok = false, summary = "Encryption self-test FAILED: ${t.message}", details = checks)
        } finally {
            plain.delete(); blob.delete(); dir.delete()
        }
    }

    private fun readBytes(f: java.io.File, n: Int): ByteArray = f.inputStream().use { VaultCrypto.readUpTo(it, n) }

    enum class Op(val working: String) {
        HIDE("Hiding…"), DRY_RUN("Counting…"), RESTORE("Restoring…"), ROTATE("Re-encrypting…"),
        UPGRADE("Upgrading encryption…"), CHECK("Checking hidden files…"), SELFTEST("Testing encryption…"),
        /** Re-encrypt under a key already installed by the UI's verified rotation flow. */
        REWRITE("Re-encrypting under the new key…"),
    }

    companion object {
        private const val TAG = "finderVault"
        private const val NOTIF_ID = 4211
        const val UNIQUE = "finder-vault"
        const val KEY_OP = "op"
        const val KEY_ONLY = "only"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val TAG_PREFIX = "vault-op:"

        /** Which operation a WorkInfo is, from the tag set at enqueue — known before it reports progress. */
        fun opOf(tags: Set<String>): Op = tags.firstNotNullOfOrNull { t ->
            if (t.startsWith(TAG_PREFIX)) runCatching { Op.valueOf(t.removePrefix(TAG_PREFIX)) }.getOrNull() else null
        } ?: Op.HIDE

        fun enqueue(context: Context, op: Op, only: String = "") {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VaultWorker>()
                    .setInputData(Data.Builder().putString(KEY_OP, op.name).putString(KEY_ONLY, only).build())
                    .addTag(TAG_PREFIX + op.name)
                    .build(),
            )
        }

        /** Back-compat shim for the adb receiver and auto-hide. */
        fun enqueue(
            context: Context,
            dry: Boolean = false,
            restore: Boolean = false,
            selfTest: Boolean = false,
            only: String = "",
            verify: Boolean = false,
            @Suppress("UNUSED_PARAMETER") purgeMissing: Boolean = false,
        ) = enqueue(
            context,
            when {
                selfTest -> Op.SELFTEST
                verify -> Op.CHECK
                restore -> Op.RESTORE
                dry -> Op.DRY_RUN
                else -> Op.HIDE
            },
            only,
        )
    }
}

/** The outcome of the last vault operation, in words, for the Private folders screen. */
data class VaultReport(
    val op: VaultWorker.Op,
    val ok: Boolean,
    val summary: String,
    val details: List<String>,
    val at: Long = System.currentTimeMillis(),
) {
    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("op", op.name).putBoolean("ok", ok).putString("summary", summary)
            .putString("details", details.joinToString("\n")).putLong("at", at)
            .apply()
    }

    companion object {
        private const val PREFS = "finder-vault-report"

        fun last(context: Context): VaultReport? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val summary = p.getString("summary", null) ?: return null
            return VaultReport(
                op = runCatching { VaultWorker.Op.valueOf(p.getString("op", "")!!) }.getOrDefault(VaultWorker.Op.HIDE),
                ok = p.getBoolean("ok", true),
                summary = summary,
                details = p.getString("details", "").orEmpty().split('\n').filter { it.isNotBlank() },
                at = p.getLong("at", 0),
            )
        }
    }
}

package ai.dusty.finderplus.media

import android.content.Context
import android.media.MediaDataSource
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The vault has files or key material, but no key on this device can open it. Import a recovery key. */
class VaultLockedException(message: String) : IOException(message)

/**
 * At-rest encryption for the media vault: hidden files are opaque `.fpv` blobs that only this app,
 * holding the key, can render.
 *
 * Bulk data is encrypted in software under random AES-256 data keys ([VaultKeyring]). The keyring is
 * wrapped by a non-exportable Android Keystore key and stored in app-private files, so unwrapping stays
 * in hardware while video streams at software-AES speed. The blob format is [VaultFormat].
 *
 * Data-loss safeguards:
 * - No silent re-keying. A key is generated only when no key files exist. If they exist but will not
 *   unwrap (Keystore reset, restore to another device), the vault reports LOCKED and waits for a
 *   recovery key; minting a new key would strand every existing file.
 * - Atomic, duplicated key storage. The keyring is written temp → fsync → rename and mirrored to a
 *   backup copy. The legacy `vault.key` is read once and never modified, so a downgrade still finds it.
 * - Verified writes. A file counts as vaulted only after its blob has been decrypted back in full and
 *   hashed identical to what was read from the original.
 * - Recovery covers every key. Exports carry the whole ring and "saved" is tracked per key, so after a
 *   rotation the app asks for a new recovery file.
 */
object VaultCrypto {

    /** Application context, captured once at startup; only used for key files and prefs. */
    @Volatile private var appContext: Context? = null
    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }
    private fun ctx(): Context = checkNotNull(appContext) { "VaultCrypto.init not called" }

    const val EXT = ".fpv"
    private const val KEYSTORE_ALIAS = "finderplus-vault-wrap"
    private const val LEGACY_KEY_FILE = "vault.key"
    private const val RING_FILE = "vault.keyring"
    private const val RING_BACKUP = "vault.keyring.bak"
    private const val PREFS = "vault"
    private const val PREF_RECOVERY_SAVED = "recovery_key_saved"
    private const val PREF_RECOVERY_KCVS = "recovery_kcvs"
    private const val TAG = "finderVault"

    fun isVaultFile(path: String?): Boolean = path?.endsWith(EXT) == true

    // =============================================================================================
    // Key state
    // =============================================================================================

    enum class KeyStatus {
        /** A key is loaded and can read and write. */
        READY,
        /** Fresh install: no key exists yet; one is created on first use. */
        NO_KEY_YET,
        /** Key material or vaulted files exist, but nothing here opens them — import a recovery key. */
        LOCKED,
    }

    /** @param vaultHasFiles whether any `.fpv` exists — files without a key also mean locked. */
    fun keyStatus(vaultHasFiles: Boolean): KeyStatus = when {
        runCatching { loadRing() }.getOrNull() != null -> KeyStatus.READY
        keyFilesExist() || vaultHasFiles -> KeyStatus.LOCKED
        else -> KeyStatus.NO_KEY_YET
    }

    /** Short fingerprint of the key new files are written under, for display ("key 3f9a…"). */
    fun activeKeyFingerprint(): String? = runCatching { loadRing()?.active?.kcv?.take(8) }.getOrNull()

    @Volatile private var ring: VaultKeyring? = null

    @Synchronized
    private fun loadRing(): VaultKeyring? {
        ring?.let { return it }
        val dir = ctx().filesDir
        for (name in listOf(RING_FILE, RING_BACKUP)) {
            val f = File(dir, name)
            if (!f.isFile) continue
            val r = runCatching { VaultKeyring.decode(unwrap(f.readBytes())) }
                .onFailure { Log.w(TAG, "keyring $name unreadable: $it") }
                .getOrNull() ?: continue
            // Re-sync the other copy: a crash between the two writes in saveRing leaves the backup a
            // key behind, and the backup is what gets read when the main copy is damaged.
            val other = File(dir, if (name == RING_FILE) RING_BACKUP else RING_FILE)
            val otherRing = runCatching { VaultKeyring.decode(unwrap(other.readBytes())) }.getOrNull()
            if (otherRing == null || !otherRing.encode().contentEquals(r.encode())) {
                runCatching { writeAtomically(other, wrap(r.encode())) }
            }
            ring = r
            return r
        }
        // One-time migration from the legacy single-key file, which is left untouched.
        val legacy = File(dir, LEGACY_KEY_FILE)
        if (legacy.isFile && !File(dir, RING_FILE).exists()) {
            val raw = runCatching { unwrap(legacy.readBytes()) }
                .onFailure { Log.w(TAG, "legacy key unreadable: $it") }
                .getOrNull() ?: return null
            val r = VaultKeyring.single(raw, legacy = true)
            saveRing(r)
            if (prefs().getBoolean(PREF_RECOVERY_SAVED, false)) addSavedKcvs(r.kcvs)
            Log.i(TAG, "migrated single vault key to keyring")
            return r
        }
        return null
    }

    @Synchronized
    private fun saveRing(r: VaultKeyring) {
        val blob = wrap(r.encode())
        val dir = ctx().filesDir
        writeAtomically(File(dir, RING_FILE), blob)
        writeAtomically(File(dir, RING_BACKUP), blob)
        ring = r
    }

    private fun keyFilesExist(): Boolean {
        val dir = ctx().filesDir
        return listOf(RING_FILE, RING_BACKUP, LEGACY_KEY_FILE).any { File(dir, it).exists() }
    }

    /** The ring for writing. Creates a key only when no key files exist; unreadable ones mean LOCKED. */
    @Synchronized
    private fun writableRing(): VaultKeyring {
        loadRing()?.let { return it }
        if (keyFilesExist()) throw VaultLockedException("The vault key on this phone cannot be opened. Import your recovery key.")
        val fresh = VaultKeyring.single(ByteArray(32).also { SecureRandom().nextBytes(it) })
        saveRing(fresh)
        Log.i(TAG, "created a new vault key")
        return fresh
    }

    /**
     * The key for one blob. FPV2 names its key; FPV1 does not, so with more than one key in the ring
     * each candidate is tried and only one that decrypts to a recognizable media header is accepted.
     * A wrong guess would restore garbage and then delete the blob, which cannot be undone.
     */
    private fun keysFor(file: File): (VaultFormat.Header) -> ByteArray? = { h ->
        val r = loadRing()
        when {
            r == null -> null
            h.version == 2 -> r.byKcv(h.kcv)
            else -> v1Key(r.entries.sortedByDescending { it.legacy }.map { it.key }, file)
        }
    }

    private fun v1Key(candidates: List<ByteArray>, file: File): ByteArray? {
        if (candidates.size == 1) return candidates[0]
        return candidates.firstOrNull { k ->
            runCatching { VaultFormat.openStream(file) { k }.use { looksLikeMedia(readUpTo(it, 16)) } }.getOrDefault(false)
        }
    }

    /** A key rotation prepared but not yet installed. */
    class PendingRotation internal constructor(internal val newKey: ByteArray, val export: Export, val newKeyId: String)

    /**
     * Step one of a rotation: a new key and a recovery file covering it and the current keys, with
     * nothing installed yet. The caller must save and verify the file before [commitRotation], so the
     * new key never exists in only one place.
     */
    fun beginRotation(passphrase: CharArray): PendingRotation {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val next = writableRing().withKey(key, active = true)
        return PendingRotation(key, Export(VaultRecovery.wrap(next, passphrase), next.kcvs), VaultFormat.kcvHex(key))
    }

    /** True when [blob] opens with [passphrase] and contains the key [keyId]. */
    fun recoveryCovers(blob: ByteArray, passphrase: CharArray, keyId: String): Boolean =
        (VaultRecovery.unwrap(blob, passphrase) as? VaultRecovery.Unwrapped.Ok)?.ring?.kcvs?.contains(keyId) == true

    /** Step two: make the new key active. Old keys stay until [retireUnusedKeys] finds them unused. */
    @Synchronized
    fun commitRotation(p: PendingRotation) {
        saveRing(writableRing().withKey(p.newKey, active = true))
    }

    /**
     * Drop keys no blob in [blobs] needs. Never drops the active key, and keeps the legacy key while
     * any FPV1 blob (which records no key id) remains. An unreadable header keeps everything.
     */
    @Synchronized
    fun retireUnusedKeys(blobs: List<File>) {
        val r = loadRing() ?: return
        val inUse = HashSet<String>()
        var needsLegacy = false
        for (b in blobs) {
            val h = runCatching { VaultFormat.readHeader(b) }.getOrNull() ?: return
            if (h.version == 1) needsLegacy = true else inUse += h.kcv.joinToString("") { "%02x".format(it) }
        }
        val legacyKcv = r.legacyKey?.let { VaultFormat.kcvHex(it) }
        val keep = r.entries.filter { it.active || it.kcv in inUse || (needsLegacy && it.kcv == legacyKcv) }
        if (keep.size < r.entries.size) {
            saveRing(VaultKeyring(keep))
            Log.i(TAG, "retired ${r.entries.size - keep.size} vault key(s) no file uses")
        }
    }

    /** Fingerprint of the key a blob was written under; null for FPV1 or an unreadable file. */
    fun blobKeyId(file: File): String? = runCatching {
        val h = VaultFormat.readHeader(file)
        if (h.version == 2) h.kcv.joinToString("") { "%02x".format(it) } else null
    }.getOrNull()

    /** FPV1, or written under a key that is no longer the active one. */
    fun needsRewrite(file: File): Boolean = runCatching {
        val h = VaultFormat.readHeader(file)
        h.version == 1 || h.kcv.joinToString("") { "%02x".format(it) } != loadRing()?.active?.kcv
    }.getOrDefault(false)

    // =============================================================================================
    // Files
    // =============================================================================================

    /**
     * Encrypt [src] into [dest] via a temp file that is fsynced, decrypted back in full and
     * hash-compared with what was read from [src] before it is renamed into place. Never overwrites an
     * existing [dest]. Returns false, leaving nothing behind, on any failure; the caller must delete
     * the original only after this returns true.
     */
    fun encryptFile(src: File, dest: File, meta: VaultFormat.Meta?): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val key = writableRing().active!!.key
            val size = src.length()
            val hash = FileOutputStream(tmp).use { out ->
                src.inputStream().buffered(1 shl 16).use { VaultFormat.encrypt(it, size, out, key, meta) }
                    .also { out.fd.sync() }
            }
            val back = VaultFormat.plaintextSha256(tmp, keysFor(tmp))
            if (!back.contentEquals(hash)) throw IOException("read-back did not match the original")
            if (dest.exists()) throw IOException("refusing to overwrite ${dest.name}")
            if (!tmp.renameTo(dest)) throw IOException("could not move the encrypted file into place")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "encrypt ${src.name} failed: $t")
            tmp.delete()
            false
        }
    }

    /** Entry point for the self-test; real hides use [encryptFile] with a [VaultFormat.Meta]. */
    fun encryptInto(src: File, dest: File): Boolean = encryptFile(src, dest, null)

    /**
     * Rewrite [src] as FPV2 under the active key at [dest], keeping its meta record (or adding [meta]
     * when the source had none). Verified like [encryptFile]; the caller swaps [dest] into place.
     */
    fun rewrite(src: File, dest: File, meta: VaultFormat.Meta?): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".part")
        return try {
            val key = writableRing().active!!.key
            val size = VaultFormat.readHeader(src).plainSize
            val m = runCatching { VaultFormat.readMeta(src, keysFor(src)) }.getOrNull() ?: meta
            val hash = FileOutputStream(tmp).use { out ->
                VaultFormat.openStream(src, keysFor(src)).use { VaultFormat.encrypt(it, size, out, key, m) }
                    .also { out.fd.sync() }
            }
            if (!VaultFormat.plaintextSha256(tmp, keysFor(tmp)).contentEquals(hash)) throw IOException("read-back mismatch")
            if (!tmp.renameTo(dest)) throw IOException("rename failed")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "rewrite ${src.name} failed: $t")
            tmp.delete()
            false
        }
    }

    /** SHA-256 of a blob's plaintext; for FPV2 also proves every chunk authenticates. */
    fun plaintextSha256(file: File): ByteArray = VaultFormat.plaintextSha256(file, keysFor(file))

    /** Decrypting stream over the whole plaintext. FPV2 streams throw on any damage. */
    fun openDecrypting(file: File): InputStream = VaultFormat.openStream(file, keysFor(file))

    fun plaintextSize(file: File): Long = VaultFormat.readHeader(file).plainSize

    /** Where the blob came from, from its encrypted meta record; null for FPV1. */
    fun readMeta(file: File): VaultFormat.Meta? = runCatching { VaultFormat.readMeta(file, keysFor(file)) }.getOrNull()

    /**
     * Seekable plaintext view for `MediaMetadataRetriever`/players: each read decrypts only the
     * 64 KiB chunks it touches (FPV2), or re-derives the CTR counter (FPV1).
     */
    fun dataSource(file: File): MediaDataSource {
        val reader = VaultFormat.Reader(file, keysFor(file))
        return object : MediaDataSource() {
            override fun getSize(): Long = reader.size
            override fun close() = reader.close()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                reader.readAt(position, buffer, offset, size)
        }
    }

    enum class Verdict { OK, OK_LEGACY, DAMAGED, NO_KEY }

    /**
     * Full check of one blob. FPV2 is decrypted end to end with every chunk authenticated, so OK is
     * proof of integrity. FPV1 has no tags; OK_LEGACY only means it decrypts to a recognizable media
     * header.
     */
    fun verify(file: File): Verdict = try {
        val h = VaultFormat.readHeader(file)
        when {
            keysFor(file)(h) == null -> Verdict.NO_KEY
            h.version == 2 -> { VaultFormat.plaintextSha256(file, keysFor(file)); Verdict.OK }
            else -> {
                val head = openDecrypting(file).use { readUpTo(it, 16) }
                if (file.length() == VaultFormat.V1_HEADER + h.plainSize && looksLikeMedia(head)) Verdict.OK_LEGACY
                else Verdict.DAMAGED
            }
        }
    } catch (t: Throwable) {
        Verdict.DAMAGED
    }

    /** CTR counter for block N (FPV1). Delegates to [VaultFormat.counterFor]; kept for existing tests. */
    fun counterFor(iv: ByteArray, blockIndex: Long): ByteArray = VaultFormat.counterFor(iv, blockIndex)

    // =============================================================================================
    // Recovery
    // =============================================================================================

    class Export(val bytes: ByteArray, val keyIds: Set<String>)

    /**
     * A recovery file for every key the vault holds, protected by [passphrase]. Slow on purpose
     * ([VaultRecovery.ITERATIONS] PBKDF2 rounds), so call it off the main thread. Creates the vault key
     * on a fresh install.
     */
    fun exportRecovery(passphrase: CharArray): Export {
        val r = writableRing()
        return Export(VaultRecovery.wrap(r, passphrase), r.kcvs)
    }

    enum class ImportResult { OK, WRONG_PASSPHRASE, NOT_A_KEY_FILE, WOULD_ORPHAN_EXISTING }

    /**
     * Add the keys in a recovery file to this device.
     *
     * Keys are merged, never swapped, so nothing that opens now stops opening because of an import.
     * When [samples] (existing blobs) are given, at least one must open under the imported keys;
     * otherwise the file belongs to a different vault and is refused.
     */
    @Synchronized
    fun importRecovery(blob: ByteArray, passphrase: CharArray, samples: List<File> = emptyList()): ImportResult {
        val imported = when (val u = VaultRecovery.unwrap(blob, passphrase)) {
            VaultRecovery.Unwrapped.NotAKeyFile -> return ImportResult.NOT_A_KEY_FILE
            VaultRecovery.Unwrapped.WrongPassphrase -> return ImportResult.WRONG_PASSPHRASE
            is VaultRecovery.Unwrapped.Ok -> u.ring
        }
        if (samples.isNotEmpty() && samples.none { opens(imported, it) }) return ImportResult.WOULD_ORPHAN_EXISTING

        val local = runCatching { loadRing() }.getOrNull()
        if (local == null && keyFilesExist()) {
            // Unreadable key files are renamed aside, never deleted: they may be the only copy of a key.
            val dir = ctx().filesDir
            for (n in listOf(RING_FILE, RING_BACKUP)) File(dir, n).takeIf { it.exists() }
                ?.renameTo(File(dir, "$n.unreadable-${System.currentTimeMillis()}"))
        }
        saveRing(local?.merge(imported) ?: imported)
        addSavedKcvs(imported.kcvs)
        return ImportResult.OK
    }

    enum class RecoveryCheck { OPENS_THIS_VAULT, OLDER_KEY, DIFFERENT_VAULT, WRONG_PASSPHRASE, NOT_A_KEY_FILE }

    /** Check a saved recovery file against this vault without changing anything. */
    fun checkRecovery(blob: ByteArray, passphrase: CharArray): RecoveryCheck {
        val imported = when (val u = VaultRecovery.unwrap(blob, passphrase)) {
            VaultRecovery.Unwrapped.NotAKeyFile -> return RecoveryCheck.NOT_A_KEY_FILE
            VaultRecovery.Unwrapped.WrongPassphrase -> return RecoveryCheck.WRONG_PASSPHRASE
            is VaultRecovery.Unwrapped.Ok -> u.ring
        }
        val local = runCatching { loadRing() }.getOrNull() ?: return RecoveryCheck.DIFFERENT_VAULT
        return when {
            local.active?.kcv in imported.kcvs -> RecoveryCheck.OPENS_THIS_VAULT
            local.kcvs.any { it in imported.kcvs } -> RecoveryCheck.OLDER_KEY
            else -> RecoveryCheck.DIFFERENT_VAULT
        }
    }

    private fun opens(r: VaultKeyring, file: File): Boolean = runCatching {
        val h = VaultFormat.readHeader(file)
        if (h.version == 2) {
            val key = r.byKcv(h.kcv) ?: return false
            VaultFormat.Reader(file) { key }.use { it.readAt(0, ByteArray(1), 0, 1) }
            true
        } else {
            // FPV1 names no key: try each, and even a single key must decrypt to real media to count.
            r.entries.any { e ->
                runCatching { VaultFormat.openStream(file) { e.key }.use { looksLikeMedia(readUpTo(it, 16)) } }.getOrDefault(false)
            }
        }
    }.getOrDefault(false)

    /**
     * True when the active key is covered by a recovery file the user saved. Tracked per key, so an
     * older recovery file stops counting after a rotation.
     */
    fun isRecoveryKeySaved(context: Context): Boolean {
        init(context)
        val active = runCatching { loadRing()?.active?.kcv }.getOrNull() ?: return false
        return active in prefs().getStringSet(PREF_RECOVERY_KCVS, emptySet()).orEmpty()
    }

    /** Record that a recovery file covering [keyIds] (default: the current ring) was saved. */
    fun markRecoveryKeySaved(context: Context, keyIds: Set<String>? = null) {
        init(context)
        addSavedKcvs(keyIds ?: runCatching { loadRing()?.kcvs }.getOrNull().orEmpty())
        prefs().edit().putBoolean(PREF_RECOVERY_SAVED, true).apply()
    }

    private fun addSavedKcvs(ids: Set<String>) {
        val p = prefs()
        val now = p.getStringSet(PREF_RECOVERY_KCVS, emptySet()).orEmpty() + ids
        p.edit().putStringSet(PREF_RECOVERY_KCVS, now).commit()
    }

    private fun prefs() = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // =============================================================================================
    // Keystore wrapping + durable writes
    // =============================================================================================

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    /** temp → fsync → rename: a crash leaves the old file or the new one, never half of either. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not save ${target.name}")
        }
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** `InputStream.readNBytes` is API 33+; this runs on every supported release. */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }

    /** Recognizes the magic numbers of common image and video containers. */
    fun looksLikeMedia(h: ByteArray): Boolean {
        fun at(i: Int) = h.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true                       // JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E -> true                       // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true                       // GIF
            at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79 && at(7) == 0x70 -> true      // MP4/MOV/HEIC ftyp
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> true      // RIFF (WEBP/AVI)
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> true      // Matroska/WEBM
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x00 -> true                       // box-prefixed mp4
            else -> false
        }
    }
}

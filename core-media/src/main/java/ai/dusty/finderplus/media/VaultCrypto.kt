package ai.rightone.finderplus.media

import android.content.Context
import android.media.MediaDataSource
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * At-rest encryption for the media vault: hidden files are opaque `.fpv` blobs that no gallery,
 * file manager, or other app can render — only this app, holding the key, can serve them.
 *
 * ### Envelope design
 *
 * Bulk crypto uses a random software AES-256 **data key**, wrapped by a non-exportable Android
 * Keystore key and stored in app-private files. Bulk AES through the Keystore itself would round-trip
 * the secure element per operation — far too slow for gigabytes of video; wrapping gives hardware
 * custody of the unwrap while the data streams at software-AES speed.
 *
 * ### Format & mode
 *
 * `FPV1 | iv(16) | plainSize(8, LE) | AES/CTR ciphertext`. CTR is chosen for **random access**:
 * a video thumbnailer must seek, and [dataSource] serves any offset by re-deriving the counter block
 * (IV + offset/16). No authentication tag — the threat model is concealment on the user's own device,
 * not tamper detection; corruption surfaces as a failed decode, same as any damaged media file.
 */
object VaultCrypto {

    /** Application context, captured once at startup; only used for the wrapped-key file. */
    @Volatile private var appContext: Context? = null
    fun init(context: Context) { if (appContext == null) appContext = context.applicationContext }
    private fun ctx(): Context = checkNotNull(appContext) { "VaultCrypto.init not called" }

    const val EXT = ".fpv"
    private const val MAGIC = 0x46505631 // "FPV1"
    private const val HEADER = 4 + 16 + 8
    private const val KEYSTORE_ALIAS = "finderplus-vault-wrap"
    private const val KEY_FILE = "vault.key"
    private val RECOVERY_MAGIC = byteArrayOf(0x46, 0x50, 0x52, 0x31) // "FPR1"
    private const val PBKDF2_ROUNDS = 210_000

    fun isVaultFile(path: String?): Boolean = path?.endsWith(EXT) == true

    /** Encrypt [src] into [dest]. Returns false (and removes a partial [dest]) on any failure. */
    fun encryptInto(src: File, dest: File): Boolean = runCatching {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(), IvParameterSpec(iv))
        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(0)
            out.writeInt(MAGIC)
            out.write(iv)
            out.writeLong(java.lang.Long.reverseBytes(src.length()))
        }
        // The cipher's trailing block is only emitted by close(), which also closes the file — so
        // the durability fsync has to happen on a reopened handle afterwards (syncing the original
        // descriptor threw SyncFailedException: it was already closed).
        CipherOutputStream(java.io.FileOutputStream(dest, true), cipher).use { enc ->
            src.inputStream().use { it.copyTo(enc, 1 shl 16) }
        }
        runCatching { RandomAccessFile(dest, "rw").use { it.fd.sync() } }
        dest.length() == HEADER + src.length()
    }.getOrElse {
        android.util.Log.w("finderVault", "encrypt failed: $it")
        dest.delete(); false
    }.also { ok -> if (!ok) dest.delete() }

    /** Decrypting stream over the whole plaintext. */
    fun openDecrypting(file: File): InputStream {
        val raf = RandomAccessFile(file, "r")
        require(raf.readInt() == MAGIC) { "not a vault file" }
        val iv = ByteArray(16).also { raf.readFully(it) }
        raf.readLong() // plaintext size, unused for streaming
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, dataKey(), IvParameterSpec(iv))
        return CipherInputStream(java.io.FileInputStream(raf.fd), cipher)
    }

    fun plaintextSize(file: File): Long = RandomAccessFile(file, "r").use { raf ->
        require(raf.readInt() == MAGIC) { "not a vault file" }
        raf.seek((4 + 16).toLong())
        java.lang.Long.reverseBytes(raf.readLong())
    }

    /**
     * Seekable plaintext view for `MediaMetadataRetriever`/players: every [MediaDataSource.readAt]
     * re-derives the CTR counter for its offset, so a frame grab deep inside a video decrypts only
     * the bytes it touches.
     */
    fun dataSource(file: File): MediaDataSource {
        val raf = RandomAccessFile(file, "r")
        require(raf.readInt() == MAGIC) { "not a vault file" }
        val iv = ByteArray(16).also { raf.readFully(it) }
        val plainSize = java.lang.Long.reverseBytes(raf.readLong())
        val key = dataKey()
        return object : MediaDataSource() {
            override fun getSize(): Long = plainSize
            override fun close() = raf.close()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= plainSize) return -1
                val want = minOf(size.toLong(), plainSize - position).toInt()
                val blockStart = position / 16 * 16
                val pre = (position - blockStart).toInt()
                val raw = ByteArray(pre + want)
                synchronized(raf) {
                    raf.seek(HEADER + blockStart)
                    raf.readFully(raw)
                }
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(counterFor(iv, blockStart / 16)))
                val plain = cipher.doFinal(raw)
                System.arraycopy(plain, pre, buffer, offset, want)
                return want
            }
        }
    }

    /** CTR counter for block N: the IV treated as a big-endian 128-bit integer, plus N. */
    fun counterFor(iv: ByteArray, blockIndex: Long): ByteArray {
        val out = iv.copyOf()
        var carry = blockIndex
        for (i in 15 downTo 0) {
            if (carry == 0L) break
            val sum = (out[i].toLong() and 0xFF) + (carry and 0xFF)
            out[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
        }
        return out
    }

    // ---- key management ----

    @Volatile private var cachedKey: SecretKey? = null

    @Synchronized
    private fun dataKey(): SecretKey {
        cachedKey?.let { return it }
        val wrapKey = wrappingKey()
        val keyFile = File(ctx().filesDir, KEY_FILE)
        val key: SecretKey = if (keyFile.exists()) {
            val blob = keyFile.readBytes()
            val iv = blob.copyOfRange(0, 12)
            val wrapped = blob.copyOfRange(12, blob.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey, javax.crypto.spec.GCMParameterSpec(128, iv))
            SecretKeySpec(c.doFinal(wrapped), "AES")
        } else {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, wrapKey)
            keyFile.writeBytes(c.iv + c.doFinal(raw))
            SecretKeySpec(raw, "AES")
        }
        cachedKey = key
        return key
    }

    // ---- recovery ----

    /**
     * Export the vault's data key, re-wrapped with a key derived from [passphrase].
     *
     * Without this the vault has a single point of failure that no amount of careful code prevents:
     * the wrapping key lives in the Keystore, and clearing app data or losing the phone destroys it,
     * taking every hidden file with it. A recovery blob turns that from "unrecoverable" into "you
     * needed the passphrase". It is useless on its own — it protects the *key*, not the files, so
     * storing it beside a backup of the vault is what makes both meaningful.
     *
     * Format: `FPR1 | salt(16) | iv(12) | AES-GCM(dataKey)`, PBKDF2-HMAC-SHA256, 210k iterations.
     */
    fun exportRecovery(passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kek = deriveKek(passphrase, salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kek)
        val wrapped = c.doFinal(dataKey().encoded)
        return RECOVERY_MAGIC + salt + c.iv + wrapped
    }

    /** What [importRecovery] did, so the UI can explain rather than just fail. */
    enum class ImportResult { OK, WRONG_PASSPHRASE, NOT_A_KEY_FILE, WOULD_ORPHAN_EXISTING }

    /**
     * Unwrap a recovery blob without installing it — used to check a key before trusting it.
     * @return the raw 32-byte data key, or null when the passphrase or format is wrong.
     */
    fun unwrapRecovery(blob: ByteArray, passphrase: CharArray): ByteArray? = runCatching {
        if (blob.size < 4 + 16 + 12 + 16) return null
        if (!blob.copyOfRange(0, 4).contentEquals(RECOVERY_MAGIC)) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            deriveKek(passphrase, blob.copyOfRange(4, 20)),
            javax.crypto.spec.GCMParameterSpec(128, blob.copyOfRange(20, 32)),
        )
        c.doFinal(blob.copyOfRange(32, blob.size)).takeIf { it.size == 32 }
    }.getOrNull()

    /** True when [rawKey] can actually decrypt [sample] — the guard against importing a stranger's key. */
    fun keyOpens(sample: File, rawKey: ByteArray): Boolean = runCatching {
        val raf = RandomAccessFile(sample, "r")
        raf.use {
            if (it.readInt() != MAGIC) return false
            val iv = ByteArray(16).also { b -> it.readFully(b) }
            val size = java.lang.Long.reverseBytes(it.readLong())
            if (size <= 0) return false
            val ct = ByteArray(minOf(64L, size).toInt())
            it.readFully(ct)
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), IvParameterSpec(iv))
            val head = c.doFinal(ct)
            // A correct key yields a real container header; a wrong one yields noise.
            head.size >= 4 && looksLikeMedia(head)
        }
    }.getOrDefault(false)

    private fun looksLikeMedia(h: ByteArray): Boolean {
        fun at(i: Int) = h.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return (at(0) == 0xFF && at(1) == 0xD8) || (at(0) == 0x89 && at(1) == 0x50) ||
            (at(0) == 0x47 && at(1) == 0x49) || (at(4) == 0x66 && at(5) == 0x74 && at(6) == 0x79) ||
            (at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46) ||
            (at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF) || (at(0) == 0 && at(1) == 0 && at(2) == 0)
    }

    /** Install [rawKey] as this device's vault key, wrapped by the Keystore. */
    fun installKey(rawKey: ByteArray) {
        val enc = Cipher.getInstance("AES/GCM/NoPadding")
        enc.init(Cipher.ENCRYPT_MODE, wrappingKey())
        File(ctx().filesDir, KEY_FILE).writeBytes(enc.iv + enc.doFinal(rawKey))
        cachedKey = SecretKeySpec(rawKey, "AES")
    }

    /**
     * Rewrite [src] (encrypted under [oldKey]) as [dest] encrypted under [newKey], with a fresh IV.
     * Verified by length before it is reported successful; the caller swaps files only on success.
     */
    fun reEncrypt(src: File, dest: File, oldKey: ByteArray, newKey: ByteArray): Boolean = runCatching {
        val raf = RandomAccessFile(src, "r")
        val plainSize: Long
        val oldIv = ByteArray(16)
        raf.use {
            if (it.readInt() != MAGIC) return false
            it.readFully(oldIv)
            plainSize = java.lang.Long.reverseBytes(it.readLong())
        }
        val dec = Cipher.getInstance("AES/CTR/NoPadding")
        dec.init(Cipher.DECRYPT_MODE, SecretKeySpec(oldKey, "AES"), IvParameterSpec(oldIv))

        val newIv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val enc = Cipher.getInstance("AES/CTR/NoPadding")
        enc.init(Cipher.ENCRYPT_MODE, SecretKeySpec(newKey, "AES"), IvParameterSpec(newIv))

        RandomAccessFile(dest, "rw").use { out ->
            out.setLength(0)
            out.writeInt(MAGIC)
            out.write(newIv)
            out.writeLong(java.lang.Long.reverseBytes(plainSize))
        }
        java.io.FileInputStream(src).use { raw ->
            raw.skip(HEADER.toLong())
            CipherOutputStream(java.io.FileOutputStream(dest, true), enc).use { encOut ->
                CipherInputStream(raw, dec).use { plain -> plain.copyTo(encOut, 1 shl 16) }
            }
        }
        dest.length() == HEADER + plainSize
    }.getOrElse { dest.delete(); false }

    /** The current data key's raw bytes — needed to re-encrypt a vault during key rotation. */
    fun currentKeyBytes(): ByteArray = dataKey().encoded

    /**
     * Install a data key from a recovery blob, replacing whatever this install had.
     *
     * [sample] is any existing vault blob: when one is present, the imported key must be able to
     * open it, otherwise the import is refused. Silently swapping in a key that cannot read the
     * local vault would turn every hidden file into permanent noise — the single worst outcome this
     * feature can produce, and it would look like a success.
     */
    fun importRecovery(blob: ByteArray, passphrase: CharArray, sample: File? = null): ImportResult {
        if (blob.size < 4 + 16 + 12 + 16 || !blob.copyOfRange(0, 4).contentEquals(RECOVERY_MAGIC)) {
            return ImportResult.NOT_A_KEY_FILE
        }
        val raw = unwrapRecovery(blob, passphrase) ?: return ImportResult.WRONG_PASSPHRASE
        if (sample != null && sample.exists() && !keyOpens(sample, raw)) return ImportResult.WOULD_ORPHAN_EXISTING
        installKey(raw)
        return ImportResult.OK
    }

    private fun importRecoveryLegacy(blob: ByteArray, passphrase: CharArray): Boolean = runCatching {
        if (blob.size < 4 + 16 + 12 + 16) return false
        if (!blob.copyOfRange(0, 4).contentEquals(RECOVERY_MAGIC)) return false
        val salt = blob.copyOfRange(4, 20)
        val iv = blob.copyOfRange(20, 32)
        val wrapped = blob.copyOfRange(32, blob.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deriveKek(passphrase, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        val raw = c.doFinal(wrapped)          // throws on a wrong passphrase: GCM authenticates
        if (raw.size != 32) return false
        installKey(raw)
        true
    }.getOrDefault(false)

    private fun deriveKek(passphrase: CharArray, salt: ByteArray): SecretKey {
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }
}

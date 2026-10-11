package ai.dusty.finderplus.media

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Every data key the vault holds: usually one, two while a key rotation is in progress.
 *
 * During a rotation the new key must be persisted alongside the old one before any file is rewritten,
 * and the old key dropped only once no file needs it, so every file stays readable if the process
 * dies part-way.
 */
class VaultKeyring(val entries: List<Entry>) {

    class Entry(val key: ByteArray, val active: Boolean, val legacy: Boolean) {
        val kcv: String = VaultFormat.kcvHex(key)
    }

    val active: Entry? get() = entries.firstOrNull { it.active } ?: entries.lastOrNull()

    /** The key FPV1 blobs were written under: the one migrated from the legacy single-key file. */
    val legacyKey: ByteArray? get() = (entries.firstOrNull { it.legacy } ?: active)?.key

    fun byKcv(kcv: ByteArray): ByteArray? {
        val hex = kcv.joinToString("") { "%02x".format(it) }
        return entries.firstOrNull { it.kcv == hex }?.key
    }

    val kcvs: Set<String> get() = entries.map { it.kcv }.toSet()

    fun withKey(key: ByteArray, active: Boolean): VaultKeyring {
        val kcv = VaultFormat.kcvHex(key)
        val kept = entries.filter { it.kcv != kcv }.map { Entry(it.key, if (active) false else it.active, it.legacy) }
        return VaultKeyring(kept + Entry(key, active, false))
    }

    fun only(kcv: String): VaultKeyring =
        VaultKeyring(entries.filter { it.kcv == kcv }.map { Entry(it.key, true, it.legacy) })

    /** Union with [other]; this ring's active key stays active. */
    fun merge(other: VaultKeyring): VaultKeyring {
        val mine = kcvs
        return VaultKeyring(entries + other.entries.filter { it.kcv !in mine }.map { Entry(it.key, false, it.legacy) })
    }

    fun encode(): ByteArray = ByteArrayOutputStream().apply {
        write(MAGIC); write(entries.size)
        for (e in entries) {
            write((if (e.active) 1 else 0) or (if (e.legacy) 2 else 0))
            write(e.key)
        }
    }.toByteArray()

    companion object {
        private val MAGIC = "FPK1".toByteArray()

        fun decode(b: ByteArray): VaultKeyring {
            require(b.size >= 5 && b.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a keyring" }
            val n = b[4].toInt() and 0xFF
            require(b.size == 5 + n * 33) { "corrupt keyring" }
            return VaultKeyring((0 until n).map { i ->
                val o = 5 + i * 33
                val flags = b[o].toInt()
                Entry(b.copyOfRange(o + 1, o + 33), active = flags and 1 != 0, legacy = flags and 2 != 0)
            })
        }

        fun single(key: ByteArray, legacy: Boolean = false) = VaultKeyring(listOf(Entry(key, true, legacy)))
    }
}

/**
 * Passphrase-protected recovery files.
 *
 * `FPR2 | iterations u32 BE | salt(16) | iv(12) | AES-256-GCM(kek, keyring)`, with the first eight
 * bytes authenticated as AAD so the iteration count cannot be lowered by editing the file.
 * KEK = PBKDF2-HMAC-SHA256(passphrase, salt, iterations).
 *
 * FPR2 carries the whole keyring, so a recovery file saved mid-rotation opens every file. 600,000
 * iterations is OWASP's minimum for PBKDF2-SHA256; FPR1 (one key, 210,000 iterations) is still
 * accepted on import.
 */
object VaultRecovery {

    const val ITERATIONS = 600_000
    private const val LEGACY_ITERATIONS = 210_000
    private val MAGIC_V1 = "FPR1".toByteArray()
    private val MAGIC_V2 = "FPR2".toByteArray()

    sealed interface Unwrapped {
        class Ok(val ring: VaultKeyring) : Unwrapped
        data object WrongPassphrase : Unwrapped
        data object NotAKeyFile : Unwrapped
    }

    fun looksLikeKeyFile(b: ByteArray): Boolean =
        b.size >= 4 && (b.copyOfRange(0, 4).contentEquals(MAGIC_V1) || b.copyOfRange(0, 4).contentEquals(MAGIC_V2))

    fun wrap(ring: VaultKeyring, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val head = MAGIC_V2 + byteArrayOf(
            (iterations ushr 24).toByte(), (iterations ushr 16).toByte(), (iterations ushr 8).toByte(), iterations.toByte(),
        )
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kek(passphrase, salt, iterations))
        c.updateAAD(head)
        val ct = c.doFinal(ring.encode())
        return head + salt + c.iv + ct
    }

    fun unwrap(b: ByteArray, passphrase: CharArray): Unwrapped {
        if (!looksLikeKeyFile(b)) return Unwrapped.NotAKeyFile
        return if (b.copyOfRange(0, 4).contentEquals(MAGIC_V2)) unwrapV2(b, passphrase) else unwrapV1(b, passphrase)
    }

    private fun unwrapV2(b: ByteArray, passphrase: CharArray): Unwrapped {
        if (b.size < 8 + 16 + 12 + 16) return Unwrapped.NotAKeyFile
        val iterations = ((b[4].toInt() and 0xFF) shl 24) or ((b[5].toInt() and 0xFF) shl 16) or
            ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        if (iterations !in 100_000..10_000_000) return Unwrapped.NotAKeyFile
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, kek(passphrase, b.copyOfRange(8, 24), iterations), GCMParameterSpec(128, b.copyOfRange(24, 36)))
            c.updateAAD(b.copyOfRange(0, 8))
            Unwrapped.Ok(VaultKeyring.decode(c.doFinal(b.copyOfRange(36, b.size))))
        }.getOrElse { Unwrapped.WrongPassphrase }
    }

    /** `FPR1 | salt(16) | iv(12) | AES-GCM(kek, dataKey)`: a single legacy key. */
    private fun unwrapV1(b: ByteArray, passphrase: CharArray): Unwrapped {
        if (b.size < 4 + 16 + 12 + 16) return Unwrapped.NotAKeyFile
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, kek(passphrase, b.copyOfRange(4, 20), LEGACY_ITERATIONS), GCMParameterSpec(128, b.copyOfRange(20, 32)))
            val raw = c.doFinal(b.copyOfRange(32, b.size))
            if (raw.size != 32) Unwrapped.NotAKeyFile else Unwrapped.Ok(VaultKeyring.single(raw, legacy = true))
        }.getOrElse { Unwrapped.WrongPassphrase }
    }

    private fun kek(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

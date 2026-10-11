package ai.dusty.finderplus.media

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A vault blob failed authentication: damaged, truncated, or encrypted under a key we do not hold. */
class VaultIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The on-disk format of a vaulted file. Pure JCA with no Android dependencies, so it is unit-tested
 * on the JVM.
 *
 * FPV2 (current):
 *
 * ```
 * header (64 B, authenticated as AAD of every record)
 *   "FPV2" | ver=2 | flags | chunkShift | 0 | plainSize u64 LE | metaLen u32 LE | 0 u32
 *   | salt (16) | key check value (8) | 0 (16)
 * meta    AES-256-GCM(fileKey, nonce = FFFFFFFF‖0, aad = header‖"meta") — where the file came from
 * chunk i AES-256-GCM(fileKey, nonce = 0‖i,        aad = header‖i‖final) — 64 KiB of plaintext each
 * ```
 *
 * - Authenticated: every chunk carries a GCM tag, and the chunk index and final-chunk flag in its AAD
 *   make truncation, reordering and splicing fail authentication too.
 * - Seekable: a reader decrypts only the 64 KiB chunk an offset falls in.
 * - Per-file keys: `fileKey = HMAC-SHA256(dataKey, label‖salt)` with a random 16-byte salt, so chunk
 *   nonces only need to be unique within a file and can simply be the chunk index.
 * - Self-describing: the encrypted meta record holds the original path, so a blob can be restored
 *   without the index database.
 * - Key check value: the first 8 bytes of `HMAC-SHA256(dataKey, label)` identify the key a blob needs,
 *   so a recovery key can be proved to open a vault rather than guessed at.
 *
 * FPV1 (read only): `"FPV1" | iv(16) | plainSize u64 LE | AES-256-CTR`. It has no tag, so damage or a
 * wrong key decrypts silently into garbage. Key rotation and "upgrade encryption" rewrite it as FPV2.
 */
object VaultFormat {

    const val MAGIC_V1 = 0x46505631 // "FPV1"
    const val MAGIC_V2 = 0x46505632 // "FPV2"
    const val V1_HEADER = 4 + 16 + 8
    const val V2_HEADER = 64
    const val CHUNK_SHIFT = 16
    const val CHUNK = 1 shl CHUNK_SHIFT
    private const val TAG = 16
    private const val KCV_LEN = 8

    private val KCV_LABEL = "finder+ vault key check v1".toByteArray()
    private val FILE_KEY_LABEL = "finder+ vault file key v1".toByteArray()
    private val META_AAD = "meta".toByteArray()

    private val random = SecureRandom()

    /** Where a vaulted file came from. Encrypted inside the blob. */
    data class Meta(
        val originalPath: String?,
        val displayName: String?,
        val mime: String?,
        val dateModified: Long,
        val vaultedAt: Long,
    ) {
        fun encode(): ByteArray = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { o ->
                o.writeByte(1)
                o.writeUTF(originalPath.orEmpty()); o.writeUTF(displayName.orEmpty()); o.writeUTF(mime.orEmpty())
                o.writeLong(dateModified); o.writeLong(vaultedAt)
            }
        }.toByteArray()

        companion object {
            fun decode(b: ByteArray): Meta = DataInputStream(b.inputStream()).use { i ->
                require(i.readUnsignedByte() == 1) { "unknown meta version" }
                Meta(
                    i.readUTF().ifEmpty { null }, i.readUTF().ifEmpty { null }, i.readUTF().ifEmpty { null },
                    i.readLong(), i.readLong(),
                )
            }
        }
    }

    /** Parsed header of either version. */
    class Header internal constructor(
        val version: Int,
        val plainSize: Long,
        val metaLen: Int,
        val salt: ByteArray,
        val kcv: ByteArray,
        val raw: ByteArray,
        val v1Iv: ByteArray?,
    ) {
        val dataStart: Long get() = if (version == 1) V1_HEADER.toLong() else V2_HEADER.toLong() + metaLen
        val chunkCount: Long get() = if (plainSize == 0L) 1 else (plainSize + CHUNK - 1) / CHUNK
        fun plainLenOf(i: Long): Int =
            if (i < chunkCount - 1) CHUNK else (plainSize - (chunkCount - 1) * CHUNK).toInt()
        fun cipherOffsetOf(i: Long): Long = dataStart + i * (CHUNK + TAG)
        val expectedFileLength: Long
            get() = if (version == 1) V1_HEADER + plainSize
            else cipherOffsetOf(chunkCount - 1) + plainLenOf(chunkCount - 1) + TAG
    }

    // ---- keys ----

    fun kcv(key: ByteArray): ByteArray = hmac(key, KCV_LABEL).copyOf(KCV_LEN)

    fun kcvHex(key: ByteArray): String = kcv(key).toHex()

    private fun fileKey(key: ByteArray, salt: ByteArray) = SecretKeySpec(hmac(key, FILE_KEY_LABEL + salt), "AES")

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    // ---- header ----

    fun readHeader(file: File): Header = RandomAccessFile(file, "r").use { readHeader(it) }

    fun readHeader(raf: RandomAccessFile): Header {
        raf.seek(0)
        return when (raf.readInt()) {
            MAGIC_V1 -> {
                val iv = ByteArray(16).also { raf.readFully(it) }
                val size = java.lang.Long.reverseBytes(raf.readLong())
                Header(1, size, 0, ByteArray(0), ByteArray(0), ByteArray(0), iv)
            }
            MAGIC_V2 -> {
                val raw = ByteArray(V2_HEADER)
                raf.seek(0); raf.readFully(raw)
                if (raw[4].toInt() != 2 || raw[6].toInt() != CHUNK_SHIFT) throw VaultIntegrityException("unsupported vault header")
                val size = le64(raw, 8)
                val metaLen = le32(raw, 16)
                if (size < 0 || metaLen < 0 || metaLen > 1 shl 20) throw VaultIntegrityException("corrupt vault header")
                Header(2, size, metaLen, raw.copyOfRange(24, 40), raw.copyOfRange(40, 48), raw, null)
            }
            else -> throw VaultIntegrityException("not a vault file")
        }
    }

    // ---- write (FPV2 only) ----

    /**
     * Encrypt exactly [size] bytes of [input] into [out] as FPV2 under [key].
     *
     * @return SHA-256 of the plaintext, so the caller can verify a decrypt reproduces it exactly.
     * @throws IOException if [input] is shorter or longer than [size] (the source changed mid-copy).
     */
    fun encrypt(input: InputStream, size: Long, out: OutputStream, key: ByteArray, meta: Meta?): ByteArray {
        require(size >= 0)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val fk = fileKey(key, salt)
        val metaPlain = meta?.encode()
        val metaLen = metaPlain?.let { it.size + TAG } ?: 0

        val header = ByteArray(V2_HEADER)
        be32(header, 0, MAGIC_V2)
        header[4] = 2; header[5] = 0; header[6] = CHUNK_SHIFT.toByte(); header[7] = 0
        putLe64(header, 8, size)
        putLe32(header, 16, metaLen)
        salt.copyInto(header, 24)
        kcv(key).copyInto(header, 40)
        out.write(header)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (metaPlain != null) {
            cipher.init(Cipher.ENCRYPT_MODE, fk, GCMParameterSpec(128, metaNonce()))
            cipher.updateAAD(header); cipher.updateAAD(META_AAD)
            out.write(cipher.doFinal(metaPlain))
        }

        val h = Header(2, size, metaLen, salt, kcv(key), header, null)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK)
        for (i in 0 until h.chunkCount) {
            val n = h.plainLenOf(i)
            readFully(input, buf, n)
            md.update(buf, 0, n)
            cipher.init(Cipher.ENCRYPT_MODE, fk, GCMParameterSpec(128, chunkNonce(i)))
            cipher.updateAAD(header); cipher.updateAAD(chunkAad(i, i == h.chunkCount - 1))
            out.write(cipher.doFinal(buf, 0, n))
        }
        if (input.read() != -1) throw IOException("source grew while it was being encrypted")
        return md.digest()
    }

    // ---- read ----

    /** The encrypted meta record, or null for FPV1 / a blob written without one. */
    fun readMeta(file: File, keyFor: (Header) -> ByteArray?): Meta? = RandomAccessFile(file, "r").use { raf ->
        val h = readHeader(raf)
        if (h.version != 2 || h.metaLen == 0) return null
        val key = keyFor(h) ?: throw VaultIntegrityException("no key for this vault file")
        val ct = ByteArray(h.metaLen)
        raf.seek(V2_HEADER.toLong()); raf.readFully(ct)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, fileKey(key, h.salt), GCMParameterSpec(128, metaNonce()))
        c.updateAAD(h.raw); c.updateAAD(META_AAD)
        Meta.decode(authenticated { c.doFinal(ct) })
    }

    /**
     * Random-access plaintext reader. FPV2 reads are authenticated per chunk and the last chunk is
     * cached, so sequential reads cost one decrypt per 64 KiB.
     */
    class Reader(file: File, keyFor: (Header) -> ByteArray?) : java.io.Closeable {
        private val raf = RandomAccessFile(file, "r")
        val header: Header = try { readHeader(raf) } catch (t: Throwable) { raf.close(); throw t }
        private val key: ByteArray = keyFor(header) ?: run { raf.close(); throw VaultIntegrityException("no key for this vault file") }
        private val fk = if (header.version == 2) fileKey(key, header.salt) else null
        private val cipher = Cipher.getInstance(if (header.version == 2) "AES/GCM/NoPadding" else "AES/CTR/NoPadding")
        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        val size: Long get() = header.plainSize

        init {
            if (header.version == 2 && raf.length() != header.expectedFileLength) {
                raf.close()
                throw VaultIntegrityException("vault file is truncated or has trailing data")
            }
        }

        /** Fills up to [len] bytes from [position]; returns the count, or -1 at end of file. */
        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= header.plainSize) return -1
            val want = minOf(len.toLong(), header.plainSize - position).toInt()
            return if (header.version == 1) readV1(position, buffer, offset, want) else readV2(position, buffer, offset, want)
        }

        private fun readV2(position: Long, buffer: ByteArray, offset: Int, want: Int): Int {
            var done = 0
            while (done < want) {
                val pos = position + done
                val i = pos / CHUNK
                val plain = chunk(i)
                val inChunk = (pos - i * CHUNK).toInt()
                val n = minOf(want - done, plain.size - inChunk)
                System.arraycopy(plain, inChunk, buffer, offset + done, n)
                done += n
            }
            return done
        }

        private fun chunk(i: Long): ByteArray {
            if (i == cachedIndex) return cached
            val n = header.plainLenOf(i)
            val ct = ByteArray(n + TAG)
            raf.seek(header.cipherOffsetOf(i)); raf.readFully(ct)
            cipher.init(Cipher.DECRYPT_MODE, fk, GCMParameterSpec(128, chunkNonce(i)))
            cipher.updateAAD(header.raw); cipher.updateAAD(chunkAad(i, i == header.chunkCount - 1))
            cached = authenticated("chunk $i") { cipher.doFinal(ct) }
            cachedIndex = i
            return cached
        }

        private fun readV1(position: Long, buffer: ByteArray, offset: Int, want: Int): Int {
            val blockStart = position / 16 * 16
            val pre = (position - blockStart).toInt()
            val raw = ByteArray(pre + want)
            raf.seek(V1_HEADER + blockStart); raf.readFully(raw)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counterFor(header.v1Iv!!, blockStart / 16)))
            val plain = cipher.doFinal(raw)
            System.arraycopy(plain, pre, buffer, offset, want)
            return want
        }

        override fun close() = raf.close()
    }

    /** Sequential stream over the whole plaintext. FPV2 chunks are authenticated as they are read. */
    fun openStream(file: File, keyFor: (Header) -> ByteArray?): InputStream {
        val header = readHeader(file)
        if (header.version == 1) {
            val key = keyFor(header) ?: throw VaultIntegrityException("no key for this vault file")
            val fis = java.io.FileInputStream(file)
            try {
                skipFully(fis, V1_HEADER.toLong())
                val c = Cipher.getInstance("AES/CTR/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(header.v1Iv))
                return CipherInputStream(fis, c)
            } catch (t: Throwable) { fis.close(); throw t }
        }
        val reader = Reader(file, keyFor)
        return object : InputStream() {
            private var pos = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = reader.readAt(pos, b, off, len)
                if (n > 0) pos += n
                return n
            }
            override fun available(): Int = (reader.size - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            override fun close() = reader.close()
        }
    }

    /**
     * Decrypt the whole file and hash it. For FPV2 every chunk is authenticated on the way, so a
     * returned hash proves the blob is intact and opens under [keyFor]'s key.
     */
    fun plaintextSha256(file: File, keyFor: (Header) -> ByteArray?): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        openStream(file, keyFor).use { input ->
            val buf = ByteArray(CHUNK)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest()
    }

    /** CTR counter for block N: the IV treated as a big-endian 128-bit integer, plus N. (FPV1.) */
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

    // ---- helpers ----

    private inline fun <T> authenticated(what: String = "record", block: () -> T): T = try {
        block()
    } catch (e: javax.crypto.AEADBadTagException) {
        throw VaultIntegrityException("vault $what failed authentication (damaged file or wrong key)", e)
    }

    private fun metaNonce() = ByteArray(12).also { for (i in 0 until 4) it[i] = 0xFF.toByte() }

    private fun chunkNonce(i: Long) = ByteArray(12).also { putBe64(it, 4, i) }

    private fun chunkAad(i: Long, last: Boolean) = ByteArray(9).also { putBe64(it, 0, i); it[8] = if (last) 1 else 0 }

    private fun readFully(input: InputStream, buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("source shrank while it was being encrypted")
            off += r
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= s
        }
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, o: Int): Int {
        var v = 0
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toInt() and 0xFF)
        return v
    }

    private fun putLe64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putLe32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putBe64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * (7 - i))).toByte() }
    private fun be32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * (3 - i))).toByte() }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

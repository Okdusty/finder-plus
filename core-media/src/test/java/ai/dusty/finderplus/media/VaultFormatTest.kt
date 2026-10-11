package ai.dusty.finderplus.media

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Checks the vault format against real ciphertext: bit-exact round trips at chunk-boundary sizes,
 * reads at unaligned offsets, and an authentication failure, not a silent decrypt into noise, on
 * damage, truncation or the wrong key.
 */
class VaultFormatTest {

    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val keyFor: (VaultFormat.Header) -> ByteArray? = { key }

    private fun payload(n: Int) = ByteArray(n) { ((it * 31 + it / 977) % 251).toByte() }

    private fun seal(plain: ByteArray, meta: VaultFormat.Meta? = null): File {
        val f = tmp.newFile()
        f.outputStream().use { out -> VaultFormat.encrypt(plain.inputStream(), plain.size.toLong(), out, key, meta) }
        return f
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test fun roundTripsAtChunkBoundaries() {
        val c = VaultFormat.CHUNK
        for (n in listOf(0, 1, 15, 16, c - 1, c, c + 1, 3 * c + 17)) {
            val plain = payload(n)
            val f = seal(plain)
            val back = VaultFormat.openStream(f, keyFor).use { it.readBytes() }
            assertArrayEquals("size $n", plain, back)
            assertEquals(n.toLong(), VaultFormat.readHeader(f).plainSize)
            assertArrayEquals(sha(plain), VaultFormat.plaintextSha256(f, keyFor))
        }
    }

    @Test fun encryptReturnsThePlaintextHash() {
        val plain = payload(200_000)
        val f = tmp.newFile()
        val hash = f.outputStream().use { VaultFormat.encrypt(plain.inputStream(), plain.size.toLong(), it, key, null) }
        assertArrayEquals(sha(plain), hash)
    }

    @Test fun randomAccessMatchesAtUnalignedOffsets() {
        val plain = payload(3 * VaultFormat.CHUNK + 1234)
        val f = seal(plain)
        VaultFormat.Reader(f, keyFor).use { r ->
            for (pos in listOf(0L, 1L, 15L, 65_535L, 65_536L, 65_537L, 131_000L, plain.size - 100L)) {
                val n = minOf(5_000, (plain.size - pos).toInt())
                val buf = ByteArray(n)
                assertEquals(n, r.readAt(pos, buf, 0, n))
                assertArrayEquals("offset $pos", plain.copyOfRange(pos.toInt(), pos.toInt() + n), buf)
            }
            assertEquals(-1, r.readAt(plain.size.toLong(), ByteArray(4), 0, 4))
        }
    }

    @Test fun ciphertextRevealsNoPlaintext() {
        val plain = ByteArray(4096) { 'A'.code.toByte() }
        val raw = seal(plain).readBytes()
        assertTrue(raw.toList().windowed(16).none { w -> w.all { it == 'A'.code.toByte() } })
    }

    @Test fun aFlippedBitFailsLoudly() {
        val plain = payload(2 * VaultFormat.CHUNK + 5)
        val f = seal(plain)
        RandomAccessFile(f, "rw").use { raf ->
            val at = VaultFormat.V2_HEADER + VaultFormat.CHUNK + 40L
            raf.seek(at); val b = raf.read(); raf.seek(at); raf.write(b xor 0x01)
        }
        expectIntegrityFailure { VaultFormat.openStream(f, keyFor).use { it.readBytes() } }
    }

    @Test fun aTamperedHeaderFailsLoudly() {
        val f = seal(payload(1000))
        RandomAccessFile(f, "rw").use { raf -> raf.seek(30); raf.write(0x5A) } // inside the salt
        expectIntegrityFailure { VaultFormat.openStream(f, keyFor).use { it.readBytes() } }
    }

    @Test fun truncationIsDetected() {
        val plain = payload(3 * VaultFormat.CHUNK)
        val f = seal(plain)
        // Drop the whole final chunk: every remaining chunk is individually valid, and only the
        // final-chunk flag plus the length check can tell.
        RandomAccessFile(f, "rw").use { it.setLength(it.length() - (VaultFormat.CHUNK + 16)) }
        expectIntegrityFailure { VaultFormat.openStream(f, keyFor).use { it.readBytes() } }
    }

    @Test fun theWrongKeyIsRejectedNotDecryptedIntoNoise() {
        val f = seal(payload(10_000))
        expectIntegrityFailure { VaultFormat.openStream(f) { otherKey }.use { it.readBytes() } }
    }

    @Test fun keyCheckValueIdentifiesTheKey() {
        val f = seal(payload(10))
        val h = VaultFormat.readHeader(f)
        assertArrayEquals(VaultFormat.kcv(key), h.kcv)
        assertNotEquals(VaultFormat.kcvHex(key), VaultFormat.kcvHex(otherKey))
    }

    @Test fun metaRecordRoundTripsAndIsAuthenticated() {
        val meta = VaultFormat.Meta("/storage/emulated/0/Download/a #b?.jpg", "a #b?.jpg", "image/jpeg", 1_700_000_000_000, 42)
        val f = seal(payload(100), meta)
        assertEquals(meta, VaultFormat.readMeta(f, keyFor))
        assertArrayEquals(payload(100), VaultFormat.openStream(f, keyFor).use { it.readBytes() })
        expectIntegrityFailure { VaultFormat.readMeta(f) { otherKey } }
        assertNull(VaultFormat.readMeta(seal(payload(5)), keyFor))
    }

    @Test fun sourceChangingMidCopyIsRefused() {
        val plain = payload(1000)
        try {
            VaultFormat.encrypt(plain.inputStream(), 2000, tmp.newFile().outputStream(), key, null)
            fail("short source accepted")
        } catch (_: java.io.IOException) {}
        try {
            VaultFormat.encrypt(plain.inputStream(), 500, tmp.newFile().outputStream(), key, null)
            fail("long source accepted")
        } catch (_: java.io.IOException) {}
    }

    @Test fun legacyV1FilesStillRead() {
        val plain = payload(70_000)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val f = tmp.newFile()
        RandomAccessFile(f, "rw").use { out ->
            out.writeInt(VaultFormat.MAGIC_V1); out.write(iv)
            out.writeLong(java.lang.Long.reverseBytes(plain.size.toLong()))
            out.write(c.doFinal(plain))
        }
        assertEquals(1, VaultFormat.readHeader(f).version)
        assertArrayEquals(plain, VaultFormat.openStream(f, keyFor).use { it.readBytes() })
        VaultFormat.Reader(f, keyFor).use { r ->
            val buf = ByteArray(1000)
            assertEquals(1000, r.readAt(65_530, buf, 0, 1000))
            assertArrayEquals(plain.copyOfRange(65_530, 66_530), buf)
        }
    }

    private fun expectIntegrityFailure(block: () -> Unit) {
        try {
            block()
            fail("damage went undetected")
        } catch (_: VaultIntegrityException) {
        }
    }
}

package ai.rightone.finderplus.media

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The counter derivation is the one piece of vault crypto that can silently corrupt data: if the
 * block counter for a random-access read disagrees by even one with the counter the sequential
 * encryptor used, the decrypted bytes are garbage that merely *looks* like a damaged file.
 *
 * CTR's requirement is exact: counter(N) = (IV as a big-endian 128-bit integer + N) mod 2^128.
 * BigInteger is the independent oracle here — the arithmetic is checked without a Cipher, because
 * android.jar's `javax.crypto` stubs shadow the real JCE in JVM unit tests. Byte-level round-trip is
 * proven separately on-device against real files.
 */
class VaultCryptoTest {

    private fun oracle(iv: ByteArray, n: Long): ByteArray {
        val sum = (BigInteger(1, iv) + BigInteger.valueOf(n)).mod(BigInteger.valueOf(2).pow(128))
        val raw = sum.toByteArray().let { if (it.size > 16) it.copyOfRange(it.size - 16, it.size) else it }
        return ByteArray(16).also { out -> System.arraycopy(raw, 0, out, 16 - raw.size, raw.size) }
    }

    @Test fun counterMatchesBigIntegerArithmetic() {
        val ivs = listOf(
            ByteArray(16) { (0xF0 + it).toByte() },
            ByteArray(16) { 0xFF.toByte() },                       // wraps the whole 128-bit space
            ByteArray(16) { if (it == 15) 0xFF.toByte() else 0 },  // single-byte carry
            ByteArray(16) { (it * 17).toByte() },
        )
        for (iv in ivs) {
            for (n in listOf(0L, 1L, 15L, 16L, 255L, 256L, 65_535L, 1L shl 20, (1L shl 40) + 7)) {
                assertArrayEquals(
                    "counter mismatch for n=$n",
                    oracle(iv, n), VaultCrypto.counterFor(iv, n),
                )
            }
        }
    }

    @Test fun counterCarriesAcrossByteBoundaries() {
        // IV ending 0xFF: +1 must carry into the previous byte, not wrap in place. Getting this wrong
        // only shows up past the first block — exactly what nobody checks by hand.
        val iv = ByteArray(16) { if (it == 15) 0xFF.toByte() else 0 }
        val next = VaultCrypto.counterFor(iv, 1)
        assertEquals(0.toByte(), next[15])
        assertEquals(1.toByte(), next[14])
    }

    @Test fun counterZeroIsIdentityAndDoesNotMutateInput() {
        val iv = ByteArray(16) { (it * 7).toByte() }
        val copy = iv.copyOf()
        assertArrayEquals(iv, VaultCrypto.counterFor(iv, 0))
        assertArrayEquals("counterFor must not mutate the IV it was handed", copy, iv)
    }

    @Test fun vaultFilesAreRecognizedByExtension() {
        assertEquals(true, VaultCrypto.isVaultFile("/x/y/IMG_1.jpg.fpv"))
        assertEquals(false, VaultCrypto.isVaultFile("/x/y/IMG_1.jpg"))
        assertEquals(false, VaultCrypto.isVaultFile(null))
    }
}

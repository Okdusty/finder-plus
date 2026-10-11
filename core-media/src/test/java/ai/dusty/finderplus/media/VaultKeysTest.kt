package ai.dusty.finderplus.media

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keyring encoding and recovery files. A recovery file is the only copy of the keys that survives
 * losing the device, so older formats must still open and a wrong passphrase must be reported.
 */
class VaultKeysTest {

    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test fun keyringRoundTripsWithFlags() {
        val a = key(); val b = key()
        val ring = VaultKeyring.single(a, legacy = true).withKey(b, active = true)
        val back = VaultKeyring.decode(ring.encode())
        assertEquals(2, back.entries.size)
        assertArrayEquals(b, back.active!!.key)
        assertArrayEquals(a, back.legacyKey)
        assertEquals(ring.kcvs, back.kcvs)
    }

    @Test fun ringFindsTheKeyABlobNeeds() {
        val a = key(); val b = key()
        val ring = VaultKeyring.single(a).withKey(b, active = false)
        assertArrayEquals(a, ring.byKcv(VaultFormat.kcv(a)))
        assertArrayEquals(b, ring.byKcv(VaultFormat.kcv(b)))
        assertEquals(null, ring.byKcv(VaultFormat.kcv(key())))
        assertArrayEquals(a, ring.active!!.key) // adding an inactive key never changes which key writes
    }

    @Test fun mergeKeepsTheLocalActiveKey() {
        val local = VaultKeyring.single(key())
        val imported = VaultKeyring.single(key())
        val merged = local.merge(imported)
        assertEquals(2, merged.entries.size)
        assertArrayEquals(local.active!!.key, merged.active!!.key)
    }

    @Test fun recoveryFileRoundTrips() {
        val ring = VaultKeyring.single(key()).withKey(key(), active = true)
        val file = VaultRecovery.wrap(ring, "anchor-basket-candle".toCharArray(), iterations = 100_000)
        val back = VaultRecovery.unwrap(file, "anchor-basket-candle".toCharArray())
        assertTrue(back is VaultRecovery.Unwrapped.Ok)
        assertEquals(ring.kcvs, (back as VaultRecovery.Unwrapped.Ok).ring.kcvs)
    }

    @Test fun wrongPassphraseIsReportedAsSuch() {
        val file = VaultRecovery.wrap(VaultKeyring.single(key()), "right".toCharArray(), iterations = 100_000)
        assertEquals(VaultRecovery.Unwrapped.WrongPassphrase, VaultRecovery.unwrap(file, "wrong".toCharArray()))
    }

    @Test fun loweringTheIterationCountBreaksTheFile() {
        val file = VaultRecovery.wrap(VaultKeyring.single(key()), "p".toCharArray(), iterations = 200_000)
        file[5] = (file[5] - 1).toByte() // 200,000 → a smaller count an attacker would prefer
        assertEquals(VaultRecovery.Unwrapped.WrongPassphrase, VaultRecovery.unwrap(file, "p".toCharArray()))
    }

    @Test fun notAKeyFileIsRecognized() {
        assertEquals(VaultRecovery.Unwrapped.NotAKeyFile, VaultRecovery.unwrap("hello".toByteArray(), "p".toCharArray()))
        assertEquals(VaultRecovery.Unwrapped.NotAKeyFile, VaultRecovery.unwrap(ByteArray(0), "p".toCharArray()))
    }

    /** Files saved by earlier versions (FPR1: one key, 210k iterations) must still restore. */
    @Test fun legacyRecoveryFilesStillOpen() {
        val raw = key()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kek = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
                .generateSecret(PBEKeySpec("old-pass".toCharArray(), salt, 210_000, 256)).encoded, "AES",
        )
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, kek)
        val wrapped = c.doFinal(raw)
        val file = "FPR1".toByteArray() + salt + c.iv + wrapped

        val back = VaultRecovery.unwrap(file, "old-pass".toCharArray())
        assertTrue(back is VaultRecovery.Unwrapped.Ok)
        val ring = (back as VaultRecovery.Unwrapped.Ok).ring
        assertArrayEquals(raw, ring.active!!.key)
        assertArrayEquals(raw, ring.legacyKey) // FPV1 blobs open with the legacy key
    }
}

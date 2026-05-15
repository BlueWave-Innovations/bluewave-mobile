package com.example.bluewave_mobile.crypto

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Pure-JVM unit tests for [CryptoManager].
 *
 * The Android Keystore provider is unavailable on a plain JVM, so we
 * mock [KeyManager] and feed [CryptoManager] a real AES-256 [SecretKey]
 * generated through the standard JCE [KeyGenerator]. This is sufficient
 * to exercise the GCM/IV/tag plumbing without involving a device.
 *
 * Step 39 acceptance criteria:
 *  * two consecutive [CryptoManager.encrypt] calls on the same plaintext
 *    must produce **different** IVs (otherwise the GCM key is broken),
 *  * the resulting ciphertexts must therefore also differ,
 *  * [CryptoManager.decrypt] must be the exact inverse of encrypt,
 *  * tampering with the ciphertext must surface as
 *    [DecryptionResult.Tampered] rather than a raw exception.
 */
class CryptoManagerIvTest {

    private fun newCryptoManager(): CryptoManager {
        val key: SecretKey = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()
        val keyManager = mockk<KeyManager>()
        every { keyManager.getOrCreateAesKey() } returns key
        return CryptoManager(keyManager)
    }

    @Test
    fun `each encrypt call generates a unique IV`() {
        val cryptoManager = newCryptoManager()
        val plaintext = "hackathon plaintext".toByteArray(Charsets.UTF_8)

        val (iv1, ct1) = cryptoManager.encrypt(plaintext)
        val (iv2, ct2) = cryptoManager.encrypt(plaintext)
        val (iv3, ct3) = cryptoManager.encrypt(plaintext)

        // Every IV must be exactly 12 bytes (96 bits) per GCM best practices.
        assertEquals(12, iv1.size)
        assertEquals(12, iv2.size)
        assertEquals(12, iv3.size)

        // IVs must NEVER repeat under the same key — reuse breaks GCM security.
        assertFalse(
            "IV reused between encrypt #1 and #2",
            iv1.contentEquals(iv2)
        )
        assertFalse(
            "IV reused between encrypt #1 and #3",
            iv1.contentEquals(iv3)
        )
        assertFalse(
            "IV reused between encrypt #2 and #3",
            iv2.contentEquals(iv3)
        )

        // Identical plaintext + different IV must produce different ciphertext.
        assertFalse(
            "Ciphertext repeated between encrypt #1 and #2",
            ct1.contentEquals(ct2)
        )
    }

    @Test
    fun `decrypt is the inverse of encrypt for valid ciphertext`() {
        val cryptoManager = newCryptoManager()
        val plaintext = "BlueWave authenticated payload \uD83D\uDD12".toByteArray(Charsets.UTF_8)

        val (iv, ciphertext) = cryptoManager.encrypt(plaintext)
        val result = cryptoManager.decrypt(iv, ciphertext)

        assertTrue("expected Success but got $result", result is DecryptionResult.Success)
        val decrypted = (result as DecryptionResult.Success).plaintext
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt detects ciphertext tampering via AEADBadTagException`() {
        val cryptoManager = newCryptoManager()
        val plaintext = "do not tamper".toByteArray(Charsets.UTF_8)

        val (iv, ciphertext) = cryptoManager.encrypt(plaintext)
        // Flip exactly one bit in the ciphertext to trigger the GCM tag
        // mismatch path inside Cipher.doFinal.
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        val result = cryptoManager.decrypt(iv, tampered)

        assertTrue(
            "expected Tampered for a one-bit-flipped ciphertext but got $result",
            result is DecryptionResult.Tampered
        )
        assertNotEquals(
            "Tampered cause must be the original AEADBadTagException",
            null,
            (result as DecryptionResult.Tampered).cause
        )
    }
}

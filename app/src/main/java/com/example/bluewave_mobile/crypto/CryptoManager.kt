package com.example.bluewave_mobile.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * High-level facade over [KeyManager] that exposes plaintext-friendly
 * `encrypt` / `decrypt` operations. Steps 23 (encrypt) and 25 (decrypt)
 * keep the methods in lock-step so the pair always shares the same
 * transformation and tag length.
 *
 * AES-256-GCM ("AES/GCM/NoPadding") generates a fresh **12-byte
 * initialization vector** for every `Cipher.init` call in encrypt mode.
 * The same IV must never be reused with the same key — doing so collapses
 * the security guarantees of GCM. To enforce this we read the IV back
 * from the freshly initialised [Cipher] via [Cipher.getIV] and store it
 * alongside the ciphertext for transmission.
 */
class CryptoManager(private val keyManager: KeyManager = KeyManager()) {

    /**
     * Encrypts [plaintext] under the AES-256-GCM key managed by
     * [KeyManager].
     *
     * @return a [Pair] where:
     *   * `first`  = the freshly generated 12-byte IV (NEVER reuse it),
     *   * `second` = the ciphertext **with the GCM authentication tag
     *                already appended** by `Cipher.doFinal`.
     *
     * Both byte arrays must be persisted together — discarding the IV
     * makes the ciphertext unrecoverable.
     */
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val key: SecretKey = keyManager.getOrCreateAesKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        // Read the IV that the provider generated for this single use.
        val iv = cipher.iv
        check(iv.size == GCM_IV_LENGTH_BYTES) {
            "Unexpected GCM IV length: ${iv.size}"
        }
        return iv to ciphertext
    }

    companion object {
        /**
         * Standard JCA transformation string for AES-256 in GCM mode
         * with no PKCS padding. Shared by encrypt and decrypt to ensure
         * symmetry between sender and receiver.
         */
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"

        /**
         * GCM mandates a 12-byte (96-bit) IV for optimal security and
         * performance. Using a longer IV silently disables some of the
         * provider's optimizations.
         */
        const val GCM_IV_LENGTH_BYTES: Int = 12

        /**
         * GCM authentication tag length (in bits) used for both encrypt
         * and decrypt. 128 is the maximum and the only value we accept.
         */
        const val GCM_TAG_LENGTH_BITS: Int = 128
    }
}

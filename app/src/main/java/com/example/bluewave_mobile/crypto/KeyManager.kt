package com.example.bluewave_mobile.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Owns the AES-256 key material backing the BlueWave end-to-end
 * encryption layer.
 *
 * Keys are generated and stored exclusively inside the
 * **Android Keystore** ("AndroidKeyStore" provider), which means the raw
 * bytes never leave the Trusted Execution Environment of the device — we
 * only ever hold a [SecretKey] handle that delegates `Cipher` operations
 * back into the Keystore.
 *
 * Cryptographic parameters (mandatory):
 *
 *  * algorithm  : AES
 *  * key size   : **256 bits**
 *  * block mode : **GCM** (Galois/Counter Mode — provides authenticated
 *                 encryption with associated data)
 *  * padding    : **NONE** — GCM is a stream cipher and adding PKCS#7
 *                 padding would break interoperability and reduce security
 *
 * Using AES-ECB or any unauthenticated mode is **explicitly prohibited**
 * by the BlueWave threat model.
 */
class KeyManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val keystoreProvider: String = ANDROID_KEYSTORE
) {

    /**
     * Returns the [SecretKey] handle for [keyAlias], generating it inside
     * the Android Keystore on first use. Subsequent calls return the same
     * handle without regenerating the underlying key material.
     */
    fun getOrCreateAesKey(): SecretKey {
        val keystore = KeyStore.getInstance(keystoreProvider).apply { load(null) }

        val existing = keystore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            keystoreProvider
        )

        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(AES_KEY_SIZE_BITS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // GCM requires a fresh IV per encryption — disable Keystore's
            // internal randomization so we can supply our own IV explicitly.
            .setRandomizedEncryptionRequired(false)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Permanently removes the AES key from the Android Keystore. Used by
     * step 35 (cleanup) and the future "log out / wipe" flow.
     */
    fun deleteKey() {
        val keystore = KeyStore.getInstance(keystoreProvider).apply { load(null) }
        if (keystore.containsAlias(keyAlias)) {
            keystore.deleteEntry(keyAlias)
        }
    }

    companion object {
        /**
         * Default Android Keystore alias used by BlueWave for its session
         * AES key. Visible to other crypto classes in the same module.
         */
        const val DEFAULT_KEY_ALIAS: String = "bluewave_session_aes_key"

        /**
         * Standard JCA provider name for the Android Keystore.
         */
        const val ANDROID_KEYSTORE: String = "AndroidKeyStore"

        /**
         * AES key size in bits. 256 is the only acceptable value for the
         * BlueWave threat model.
         */
        const val AES_KEY_SIZE_BITS: Int = 256
    }
}

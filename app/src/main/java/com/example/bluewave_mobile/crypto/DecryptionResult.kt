package com.example.bluewave_mobile.crypto

/**
 * Sum type returned by [CryptoManager.decrypt].
 *
 * Forces the caller to explicitly handle the case where the
 * authentication tag does not match the ciphertext, instead of letting
 * a low-level [javax.crypto.AEADBadTagException] bubble up across module
 * boundaries.
 */
sealed interface DecryptionResult {

    /**
     * The ciphertext was authentic and successfully decrypted.
     *
     * @property plaintext The recovered plaintext bytes.
     */
    data class Success(val plaintext: ByteArray) : DecryptionResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return plaintext.contentEquals(other.plaintext)
        }

        override fun hashCode(): Int = plaintext.contentHashCode()
    }

    /**
     * The GCM authentication tag rejected the ciphertext, meaning either
     * the IV or the payload were tampered with in transit (or the wrong
     * key was used). The original cause is preserved for diagnostics.
     *
     * @property cause The underlying [javax.crypto.AEADBadTagException]
     *                 (or any subclass thereof) thrown by the JCE provider.
     */
    data class Tampered(val cause: Throwable) : DecryptionResult
}

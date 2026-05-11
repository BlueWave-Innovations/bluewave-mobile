package com.example.bluewave_mobile.crypto

/**
 * Behind-the-scenes E2EE primitive used by `MessageRepositoryImpl`
 * to encrypt every chat payload before it crosses the radio.
 *
 * Modelling the engine as an interface (rather than calling
 * libsignal directly from the repository) gives us two things:
 *
 *  * **Testability.** `MessageRepositoryImpl` can be unit-tested on
 *    the JVM with a fake engine that just round-trips bytes — no
 *    Signal stores, no JNI, no static state. The real
 *    [com.example.bluewave_mobile.crypto.LibSignalEngine] is only
 *    instantiated on Android.
 *  * **Reversibility.** If we ever need to swap libsignal out for a
 *    different ratchet (Olm, MLS, …) the repository is shielded by
 *    this interface — only the engine gets rewritten.
 *
 * Concurrency contract: every method on this interface MUST be
 * safe to call from arbitrary coroutines. Implementations are
 * expected to serialise their internal stores with a `Mutex` so
 * concurrent encrypt/decrypt for two peers cannot corrupt the
 * underlying Signal state.
 */
interface SignalEngine {

    /**
     * Output of an [encrypt] call paired with the wire tag that the
     * remote peer needs to re-route the bytes back into the right
     * `SessionCipher.decrypt` overload.
     */
    data class Ciphertext(val type: Type, val bytes: ByteArray) {
        /** Frame tag a la [com.example.bluewave_mobile.network.BlueWaveFrame.Type]. */
        enum class Type {
            /** Regular Signal message — peer already has a session. */
            SIGNAL_MESSAGE,

            /**
             * The very first encrypted message after a key bundle
             * exchange. The body embeds the bootstrap data the peer
             * needs to build its half of the session.
             */
            PREKEY_SIGNAL_MESSAGE,
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ciphertext) return false
            return type == other.type && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()
    }

    /**
     * Returns `true` once a Signal session has been established with
     * [macAddress] — i.e. the peer's key bundle has already been
     * processed via [processPeerKeyBundle].
     */
    suspend fun hasSession(macAddress: String): Boolean

    /**
     * Returns the local key bundle (libsignal `PreKeyBundle`)
     * serialised as bytes. The bundle is meant to be sent to the
     * peer wrapped in a `BlueWaveFrame.Type.KEY_BUNDLE` envelope so
     * the peer can run [processPeerKeyBundle] against it.
     */
    suspend fun localKeyBundle(): ByteArray

    /**
     * Consumes a key bundle [bytes] received from [macAddress] and
     * runs `SessionBuilder.process` against it. After this call
     * succeeds, [hasSession] for the same MAC returns `true` and
     * subsequent [encrypt] calls will produce a
     * [Ciphertext.Type.PREKEY_SIGNAL_MESSAGE] for the very first
     * outbound message and [Ciphertext.Type.SIGNAL_MESSAGE] for
     * every subsequent one.
     *
     * @throws SignalEngineException when the bundle cannot be parsed
     *         or its identity key fails libsignal's basic sanity
     *         checks.
     */
    suspend fun processPeerKeyBundle(macAddress: String, bytes: ByteArray)

    /**
     * Encrypts [plaintext] for [macAddress] using the active Signal
     * session, advancing the Double Ratchet by exactly one step.
     *
     * @throws SignalEngineException when no session exists yet for
     *         the peer (the caller is expected to handshake via
     *         [processPeerKeyBundle] first) or when libsignal
     *         refuses the encrypt call for any other reason.
     */
    suspend fun encrypt(macAddress: String, plaintext: ByteArray): Ciphertext

    /**
     * Decrypts a regular `SignalMessage` blob received from
     * [macAddress]. The receiver MUST already have a session
     * established with the peer.
     *
     * @throws SignalEngineException when the session is missing,
     *         the MAC tag is invalid (replay or tamper), or the
     *         message is otherwise malformed.
     */
    suspend fun decryptSignalMessage(macAddress: String, ciphertext: ByteArray): ByteArray

    /**
     * Decrypts the first inbound message after a key bundle has
     * been pushed in the *opposite* direction — libsignal's
     * `PreKeySignalMessage` carries the bootstrap material so the
     * receiver can build its half of the session from a single
     * frame, even if it has not received an explicit
     * `KEY_BUNDLE` frame yet.
     *
     * @throws SignalEngineException on tamper / replay / parse
     *         failures. The repository is expected to surface those
     *         to the UI as a `Tampered` chat row.
     */
    suspend fun decryptPreKeyMessage(macAddress: String, ciphertext: ByteArray): ByteArray

    /**
     * Drops every key, prekey and session that the engine knows
     * about. Used by the "Reset E2EE" debug action and by tests
     * between scenarios so cross-contamination cannot leak between
     * runs.
     */
    suspend fun reset()

    /**
     * Drops the Signal session record for a single peer so the next
     * `KEY_BUNDLE` exchange with that peer rebuilds the Double
     * Ratchet from scratch.
     *
     * Called by `MessageRepositoryImpl.onPeerLinkDown` when the
     * RFCOMM session ends so a stale ratchet — typically the result
     * of the peer process being killed and losing its in-memory
     * libsignal store — cannot poison the next link-up. The local
     * identity and prekey material are kept; only the per-peer
     * session is forgotten.
     *
     * Safe to call for a peer we have never handshaked with —
     * implementations are expected to no-op in that case.
     */
    suspend fun resetPeerSession(macAddress: String)
}

/**
 * Generic wrapper that the engine raises whenever an underlying
 * libsignal call fails. The [cause] is preserved verbatim so the
 * UI / logs can pinpoint the JCE / Signal exception that bubbled
 * up.
 */
class SignalEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

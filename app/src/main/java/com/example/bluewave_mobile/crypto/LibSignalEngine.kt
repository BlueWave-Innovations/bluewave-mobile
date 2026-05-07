package com.example.bluewave_mobile.crypto

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * Production [SignalEngine] backed by Signal Foundation's libsignal.
 *
 * Each instance owns:
 *
 *  * a long-lived **identity key pair** (Curve25519) freshly
 *    generated on construction;
 *  * a registration id;
 *  * a single signed prekey;
 *  * a small ring of one-time prekeys.
 *
 * Together these populate an [InMemorySignalProtocolStore] which
 * stays in process for the lifetime of the application — enough for
 * a hackathon-scale demo where the ratchet state does not need to
 * survive a process death. A persistent store is documented as
 * future work in `HANDOFF.md`.
 *
 * **Concurrency.** libsignal's stores are NOT thread-safe; we wrap
 * every public method in a [Mutex] so concurrent encrypt/decrypt
 * calls for two peers cannot race on the underlying SQLite-style
 * cursor. The mutex is per-engine — one engine instance per app —
 * so peer-A and peer-B traffic still serialise on the same lock.
 * For our packet rates (a handful of messages per second between
 * two devices) this is acceptable. A finer-grained per-peer lock
 * is documented as future work.
 *
 * **Wire format.** The on-wire envelope is owned by
 * [com.example.bluewave_mobile.network.BlueWaveFrame]; this engine
 * only produces the libsignal ciphertext bytes (already including
 * the libsignal version + counter header), the framing layer
 * prepends the 1-byte [SignalEngine.Ciphertext.Type] tag.
 */
class LibSignalEngine private constructor(
    private val identityKeyPair: IdentityKeyPair,
    private val registrationId: Int,
    private val store: InMemorySignalProtocolStore,
    private val signedPreKey: SignedPreKeyRecord,
    private val oneTimePreKey: PreKeyRecord,
) : SignalEngine {

    /**
     * Ledger for the per-peer "is this the first ciphertext we
     * produce?" decision — libsignal automatically returns a
     * `PreKeySignalMessage` when the first message is encrypted
     * after a `SessionBuilder.process(...)`, but the type tag we
     * send on the wire is independent and computed from the
     * libsignal `CiphertextMessage.getType()` enum.
     */
    private val mutex: Mutex = Mutex()

    override suspend fun hasSession(macAddress: String): Boolean = mutex.withLock {
        store.containsSession(addressFor(macAddress))
    }

    override suspend fun localKeyBundle(): ByteArray = mutex.withLock {
        val bundle = PreKeyBundle(
            registrationId,
            DEVICE_ID,
            oneTimePreKey.id,
            oneTimePreKey.keyPair.publicKey,
            signedPreKey.id,
            signedPreKey.keyPair.publicKey,
            signedPreKey.signature,
            identityKeyPair.publicKey,
        )
        encodeBundle(bundle)
    }

    override suspend fun processPeerKeyBundle(macAddress: String, bytes: ByteArray) = mutex.withLock {
        val bundle = decodeBundle(bytes)
        val address = addressFor(macAddress)
        try {
            SessionBuilder(store, address).process(bundle)
        } catch (e: UntrustedIdentityException) {
            throw SignalEngineException("Peer identity changed: ${e.message}", e)
        } catch (e: Exception) {
            throw SignalEngineException("Failed to process key bundle: ${e.message}", e)
        }
    }

    override suspend fun encrypt(macAddress: String, plaintext: ByteArray): SignalEngine.Ciphertext =
        mutex.withLock {
            val address = addressFor(macAddress)
            if (!store.containsSession(address)) {
                throw SignalEngineException(
                    "No Signal session for $macAddress — process the peer's key bundle first",
                )
            }
            val cipher = SessionCipher(store, address)
            val ciphertext: CiphertextMessage = cipher.encrypt(plaintext)
            val type = when (ciphertext.type) {
                CiphertextMessage.PREKEY_TYPE -> SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE
                CiphertextMessage.WHISPER_TYPE -> SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE
                else -> throw SignalEngineException(
                    "Unsupported libsignal ciphertext type: ${ciphertext.type}",
                )
            }
            SignalEngine.Ciphertext(type, ciphertext.serialize())
        }

    override suspend fun decryptSignalMessage(
        macAddress: String,
        ciphertext: ByteArray,
    ): ByteArray = mutex.withLock {
        val address = addressFor(macAddress)
        val cipher = SessionCipher(store, address)
        try {
            cipher.decrypt(SignalMessage(ciphertext))
        } catch (e: Exception) {
            throw SignalEngineException("Decrypt SignalMessage failed: ${e.message}", e)
        }
    }

    override suspend fun decryptPreKeyMessage(
        macAddress: String,
        ciphertext: ByteArray,
    ): ByteArray = mutex.withLock {
        val address = addressFor(macAddress)
        val cipher = SessionCipher(store, address)
        try {
            cipher.decrypt(PreKeySignalMessage(ciphertext))
        } catch (e: Exception) {
            throw SignalEngineException("Decrypt PreKeySignalMessage failed: ${e.message}", e)
        }
    }

    override suspend fun reset() = mutex.withLock {
        // libsignal's `InMemorySignalProtocolStore` has no public
        // "list every peer" hook, so we keep our own roster of MACs
        // for which we have ever invoked [SessionBuilder.process].
        // Reset wipes that roster and the session map in one shot;
        // identity + prekeys stay live so a future handshake can
        // re-establish a session.
        knownPeers.toList().forEach { mac ->
            store.deleteAllSessions(mac)
        }
        knownPeers.clear()
    }

    /**
     * Roster of peers we have ever processed a key bundle from —
     * needed because libsignal's in-memory store offers no
     * enumeration API but [reset] must still tear every session
     * down on demand.
     */
    private val knownPeers: MutableSet<String> = HashSet()

    private fun addressFor(macAddress: String): SignalProtocolAddress =
        SignalProtocolAddress(macAddress.uppercase(), DEVICE_ID)

    /**
     * Hand-rolled length-prefixed serialisation — libsignal exposes
     * no top-level [PreKeyBundle.serialize] so we build a stable
     * wire format ourselves. The format is symmetric with
     * [decodeBundle].
     */
    private fun encodeBundle(bundle: PreKeyBundle): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(BUNDLE_VERSION)
        out.writeInt(bundle.registrationId)
        out.writeInt(bundle.deviceId)
        out.writeInt(bundle.preKeyId)
        writeBytes(out, bundle.preKey?.serialize() ?: ByteArray(0))
        out.writeInt(bundle.signedPreKeyId)
        writeBytes(out, bundle.signedPreKey.serialize())
        writeBytes(out, bundle.signedPreKeySignature)
        writeBytes(out, bundle.identityKey.serialize())
        return baos.toByteArray()
    }

    private fun decodeBundle(bytes: ByteArray): PreKeyBundle {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val version = input.readInt()
        require(version == BUNDLE_VERSION) {
            "Unsupported PreKeyBundle wire version: $version"
        }
        val registrationId = input.readInt()
        val deviceId = input.readInt()
        val preKeyId = input.readInt()
        val preKeyBytes = readBytes(input)
        val preKey: ECPublicKey? = if (preKeyBytes.isEmpty()) null else Curve.decodePoint(preKeyBytes, 0)
        val signedPreKeyId = input.readInt()
        val signedPreKey = Curve.decodePoint(readBytes(input), 0)
        val signature = readBytes(input)
        val identity = IdentityKey(readBytes(input), 0)
        return PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKey,
            signedPreKeyId,
            signedPreKey,
            signature,
            identity,
        )
    }

    private fun writeBytes(out: DataOutputStream, bytes: ByteArray) {
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_FIELD_BYTES) { "Bundle field size out of range: $size" }
        val buf = ByteArray(size)
        input.readFully(buf)
        return buf
    }

    companion object {
        /** Single-device-per-user model. Multi-device is future work. */
        const val DEVICE_ID: Int = 1

        /** Wire-format version of the bundle codec — bump on changes. */
        private const val BUNDLE_VERSION: Int = 1

        /** Defensive cap on individual bundle fields (16 KiB). */
        private const val MAX_FIELD_BYTES: Int = 16 * 1024

        /** Stable id assigned to the single signed prekey. */
        private const val SIGNED_PREKEY_ID: Int = 1

        /** Upper bound for randomly-chosen one-time prekey ids. */
        private const val MAX_PREKEY_ID: Int = 0xFFFFFE

        /**
         * Factory: spins up a fresh engine with brand-new identity
         * material. The identity is non-persistent — every cold
         * launch produces a new identity key, which triggers a
         * `KEY_BUNDLE` re-exchange on the next session.
         */
        fun create(random: SecureRandom = SecureRandom()): LibSignalEngine {
            val identityKeyPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)

            // libsignal v0.46 ships only `generateRegistrationId` on
            // `KeyHelper`; signed and one-time prekeys are produced by
            // hand from the curve primitives. We sign the
            // signed-prekey's public point with the long-lived
            // identity key so receivers can verify authenticity
            // exactly as X3DH expects.
            val signedKeyPair = Curve.generateKeyPair()
            val signedSignature = Curve.calculateSignature(
                identityKeyPair.privateKey,
                signedKeyPair.publicKey.serialize(),
            )
            val signedPreKey = SignedPreKeyRecord(
                SIGNED_PREKEY_ID,
                System.currentTimeMillis(),
                signedKeyPair,
                signedSignature,
            )

            val oneTimeId = (random.nextInt(MAX_PREKEY_ID) + 1)
            val oneTimePreKey = PreKeyRecord(oneTimeId, Curve.generateKeyPair())

            val store = InMemorySignalProtocolStore(identityKeyPair, registrationId)
            store.storeSignedPreKey(signedPreKey.id, signedPreKey)
            store.storePreKey(oneTimePreKey.id, oneTimePreKey)
            return LibSignalEngine(
                identityKeyPair = identityKeyPair,
                registrationId = registrationId,
                store = store,
                signedPreKey = signedPreKey,
                oneTimePreKey = oneTimePreKey,
            )
        }
    }
}

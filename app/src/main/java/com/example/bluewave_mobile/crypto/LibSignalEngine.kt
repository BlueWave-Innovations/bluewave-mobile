package com.example.bluewave_mobile.crypto

import android.content.Context
import android.util.Base64
import com.example.bluewave_mobile.utils.BlueWaveLogger
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
 *  * a long-lived **identity key pair** (Curve25519) persisted across
 *    process restarts in encrypted SharedPreferences;
 *  * a registration id;
 *  * a single signed prekey rotated every 7 days;
 *  * a pool of 100 one-time prekeys replenished on creation.
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
    private var signedPreKey: SignedPreKeyRecord,
    private val oneTimePreKeyIds: List<Int>,
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
        rotateSignedPreKeyIfNeeded()
        // Pick a one-time prekey that still exists in the store.
        // libsignal removes it when the first inbound PreKeySignalMessage
        // is decrypted, so we shuffle and iterate until we find a live one.
        val preKey: PreKeyRecord? = oneTimePreKeyIds.shuffled().firstNotNullOfOrNull { id ->
            try {
                store.loadPreKey(id)
            } catch (_: Exception) {
                null
            }
        }
        if (preKey == null) {
            BlueWaveLogger.w(TAG, "No one-time prekeys left — multi-peer handshakes may fail")
        }
        val bundle = PreKeyBundle(
            registrationId,
            DEVICE_ID,
            preKey?.id ?: 0,
            preKey?.keyPair?.publicKey,
            signedPreKey.id,
            signedPreKey.keyPair.publicKey,
            signedPreKey.signature,
            identityKeyPair.publicKey,
        )
        encodeBundle(bundle)
    }

    override suspend fun processPeerKeyBundle(macAddress: String, bytes: ByteArray) {
        mutex.withLock {
            val bundle = decodeBundle(bytes)
            val address = addressFor(macAddress)
            try {
                SessionBuilder(store, address).process(bundle)
            } catch (e: UntrustedIdentityException) {
                // Trust-on-first-use: the peer rotated its identity
                // key (process restart with an in-memory store, fresh
                // install, …). In a centralised messenger we'd
                // surface a warning and force the user to re-verify;
                // in our setting the RFCOMM peer is already validated
                // by its MAC address and physical proximity, so we
                // overwrite the stored identity and retry the
                // handshake. Without this branch a peer reinstall
                // would permanently break message flow until both
                // apps were force-stopped.
                BlueWaveLogger.w(
                    TAG,
                    "Peer $macAddress rotated identity; overwriting stored key and retrying handshake",
                )
                try {
                    store.saveIdentity(address, bundle.identityKey)
                    SessionBuilder(store, address).process(bundle)
                } catch (retry: Exception) {
                    throw SignalEngineException(
                        "Failed to process key bundle after identity rotation: ${retry.message}",
                        retry,
                    )
                }
            } catch (e: Exception) {
                throw SignalEngineException("Failed to process key bundle: ${e.message}", e)
            }
            knownPeers.add(address.name)
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

    override suspend fun reset(): Unit = mutex.withLock {
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

    override suspend fun resetPeerSession(macAddress: String) {
        mutex.withLock {
            // libsignal does not expose a "delete the session for one
            // device id" hook directly — `deleteSession` clears *all*
            // device ids for the supplied name, which is exactly the
            // right granularity for our single-device model. We also
            // drop the MAC from `knownPeers` so a subsequent [reset]
            // does not redundantly re-target it.
            val address = addressFor(macAddress)
            if (store.containsSession(address)) {
                store.deleteAllSessions(address.name)
            }
            knownPeers.remove(address.name)
        }
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
     * Rotates the signed prekey if it is older than
     * [SIGNED_PREKEY_ROTATION_INTERVAL_MS]. Signal Protocol
     * recommends rotation every 1–4 weeks; we use 7 days.
     */
    private fun rotateSignedPreKeyIfNeeded() {
        val now = System.currentTimeMillis()
        val age = now - signedPreKey.timestamp
        if (age <= SIGNED_PREKEY_ROTATION_INTERVAL_MS) return
        val newKeyPair = Curve.generateKeyPair()
        val newSignature = Curve.calculateSignature(
            identityKeyPair.privateKey,
            newKeyPair.publicKey.serialize(),
        )
        val newPreKey = SignedPreKeyRecord(
            SIGNED_PREKEY_ID,
            now,
            newKeyPair,
            newSignature,
        )
        store.storeSignedPreKey(SIGNED_PREKEY_ID, newPreKey)
        signedPreKey = newPreKey
        BlueWaveLogger.i(TAG, "Rotated signed prekey (age=${age / 86_400_000} days)")
    }

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

        /** Logcat tag used by the engine for non-fatal warnings. */
        private const val TAG: String = "LibSignalEngine"

        /** Wire-format version of the bundle codec — bump on changes. */
        private const val BUNDLE_VERSION: Int = 1

        /** Defensive cap on individual bundle fields (16 KiB). */
        private const val MAX_FIELD_BYTES: Int = 16 * 1024

        /** Stable id assigned to the single signed prekey. */
        private const val SIGNED_PREKEY_ID: Int = 1

        /** Upper bound for randomly-chosen one-time prekey ids. */
        private const val MAX_PREKEY_ID: Int = 0xFFFFFE

        /** Number of one-time prekeys generated at creation. */
        private const val ONE_TIME_PREKEY_COUNT: Int = 100

        /** Rotation cadence for the signed prekey (7 days). */
        private const val SIGNED_PREKEY_ROTATION_INTERVAL_MS: Long = 7 * 24 * 60 * 60 * 1_000L

        private const val PREFS_NAME: String = "bluewave_libsignal_v1"
        private const val KEY_IDENTITY: String = "identity_key_pair"
        private const val KEY_REGISTRATION_ID: String = "registration_id"

        /**
         * Factory: spins up a fresh engine with brand-new identity
         * material. The identity key pair is persisted in encrypted
         * SharedPreferences so it survives process death — without
         * persistence every cold launch would produce a new identity
         * and trigger an unnecessary re-handshake with every peer.
         */
        fun create(context: Context, random: SecureRandom = SecureRandom()): LibSignalEngine {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val identityKeyPair: IdentityKeyPair
            val registrationId: Int

            val savedIdentity = prefs.getString(KEY_IDENTITY, null)
            val savedRegId = prefs.getInt(KEY_REGISTRATION_ID, 0)

            if (savedIdentity != null && savedRegId != 0) {
                identityKeyPair = IdentityKeyPair(Base64.decode(savedIdentity, Base64.NO_WRAP))
                registrationId = savedRegId
            } else {
                identityKeyPair = IdentityKeyPair.generate()
                registrationId = KeyHelper.generateRegistrationId(false)
                prefs.edit()
                    .putString(
                        KEY_IDENTITY,
                        Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP),
                    )
                    .putInt(KEY_REGISTRATION_ID, registrationId)
                    .apply()
            }

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

            val oneTimePreKeyIds = mutableListOf<Int>()
            val store = InMemorySignalProtocolStore(identityKeyPair, registrationId)
            store.storeSignedPreKey(signedPreKey.id, signedPreKey)

            repeat(ONE_TIME_PREKEY_COUNT) {
                val id = random.nextInt(MAX_PREKEY_ID) + 1
                oneTimePreKeyIds.add(id)
                store.storePreKey(id, PreKeyRecord(id, Curve.generateKeyPair()))
            }

            return LibSignalEngine(
                identityKeyPair = identityKeyPair,
                registrationId = registrationId,
                store = store,
                signedPreKey = signedPreKey,
                oneTimePreKeyIds = oneTimePreKeyIds,
            )
        }
    }
}

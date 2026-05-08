package com.example.bluewave_mobile.data

import android.util.Log
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.SignalEngine
import com.example.bluewave_mobile.crypto.SignalEngineException
import com.example.bluewave_mobile.network.BlueWaveFrame
import com.example.bluewave_mobile.network.MessageTransport
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.preferences.LocalProfileCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Concrete implementation of [MessageRepository] serving as the Single Source of Truth.
 *
 * This class coordinates data between the Room database (via [MessageDao])
 * and the Bluetooth network layer. All data flows through the database first:
 * incoming messages are persisted before the UI sees them, ensuring consistency.
 *
 * Dependencies are injected via constructor for testability — no direct
 * instantiation of DAO or database inside this class.
 *
 * **Encryption posture.**
 *  * AES-256-GCM via [cryptoManager] is used for **encryption at-rest**
 *    in Room. Every persisted row carries a fresh 12-byte IV.
 *  * libsignal's X3DH + Double Ratchet via [signalEngine] is used for
 *    **end-to-end encryption on the wire**. When [signalEngine] is
 *    `null` the repository falls back to plaintext-on-wire (the
 *    legacy mode used by JVM unit tests that do not link against
 *    the libsignal native library).
 *  * The on-wire envelope is [BlueWaveFrame] — a 1-byte type tag
 *    followed by the libsignal ciphertext or a serialized
 *    `PreKeyBundle`.
 *
 * @property messageDao The Room DAO for message CRUD operations.
 * @property cryptoManager AES-256-GCM facade used to encrypt messages
 *                          for at-rest storage in the local database.
 * @property transport     Optional [MessageTransport] used to push
 *                          bytes to the peer over RFCOMM. Defaults to
 *                          `null` so unit tests can run without a
 *                          Bluetooth radio.
 * @property signalEngine  Optional [SignalEngine] used for E2EE on
 *                          the wire. Defaults to `null` so JVM unit
 *                          tests that do not load libsignal's native
 *                          library still drive the legacy plaintext
 *                          wire path.
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val cryptoManager: CryptoManager = CryptoManager(),
    private val transport: MessageTransport? = null,
    private val signalEngine: SignalEngine? = null,
    private val peerProfileDao: PeerProfileDao? = null,
    private val localProfileProvider: suspend () -> LocalProfile = { LocalProfile.EMPTY },
) : MessageRepository {

    override fun getMessagesByDevice(macAddress: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesByDevice(macAddress)
    }

    override fun getLatestMessagePerDevice(): Flow<List<MessageEntity>> {
        return messageDao.getLatestMessagePerDevice()
    }

    override fun observeAllConversations(): Flow<List<ConversationSummary>> {
        return combine(
            messageDao.getLatestMessagePerDevice(),
            messageDao.observeUnreadCounts(),
        ) { lasts, unread ->
            // Index unread counts by uppercase MAC for stable lookups.
            val unreadByMac: Map<String, Int> = unread.associate {
                it.macAddress.uppercase() to it.unreadCount
            }
            lasts.map { last ->
                val key = last.macAddress.uppercase()
                ConversationSummary(
                    macAddress = key,
                    lastMessage = last,
                    unreadCount = unreadByMac[key] ?: 0,
                )
            }
        }.distinctUntilChanged()
    }

    override suspend fun markPeerAsRead(macAddress: String) {
        messageDao.markPeerAsRead(macAddress.uppercase())
    }

    override fun observeSessionState(macAddress: String): Flow<E2EEState> {
        return sessionStateFor(macAddress).asStateFlow()
    }

    override fun observePeerProfile(macAddress: String): Flow<PeerProfileEntity?> {
        val dao = peerProfileDao ?: return flowOf(null)
        return dao.observeProfile(macAddress.uppercase()).distinctUntilChanged()
    }

    override fun observeAllPeerProfiles(): Flow<List<PeerProfileEntity>> {
        val dao = peerProfileDao ?: return flowOf(emptyList())
        return dao.observeAll().distinctUntilChanged()
    }

    override suspend fun onLocalProfileChanged() {
        // Re-broadcast the local profile to every peer we have a
        // SECURE Signal session with. New peers will receive the
        // profile through the per-session push driven by
        // [pushLocalProfileIfReady] right after the handshake
        // settles.
        val engine = signalEngine ?: return
        val activeTransport = transport ?: return
        val peers: List<String> = synchronized(sessionStates) {
            sessionStates.entries.filter { it.value.value == E2EEState.SECURE }.map { it.key }
        }
        if (peers.isEmpty()) return
        val profile = runCatching { localProfileProvider() }.getOrNull() ?: return
        val payload = LocalProfileCodec.encode(profile)
        for (peer in peers) {
            shipProfilePayload(engine, activeTransport, peer, payload)
        }
    }

    override suspend fun onPeerLinkUp(macAddress: String) {
        // Eager handshake: as soon as the radio link is up, push our
        // local key bundle so the peer can derive its sending session.
        // This is symmetric — whichever side initiated the connect,
        // both peers fire this hook on attach.
        sendLocalKeyBundleIfNeeded(macAddress.uppercase())
    }

    override suspend fun insertMessage(message: MessageEntity): Long {
        return messageDao.insertMessage(message)
    }

    override suspend fun processIncomingMessage(
        macAddress: String,
        senderName: String,
        rawData: ByteArray
    ) {
        val key = macAddress.uppercase()
        val engine = signalEngine
        if (engine == null) {
            // Legacy plaintext-on-wire mode (used by JVM unit tests
            // that do not link libsignal). [rawData] is the plaintext
            // UTF-8 payload of one fully reassembled BlueWave wire
            // frame; we re-encrypt it with the local AES key for
            // encryption-at-rest so the on-disk shape stays
            // identical to outgoing messages.
            persistInboundPlaintext(macAddress, senderName, rawData)
            return
        }

        // E2EE mode: every inbound payload is a [BlueWaveFrame]
        // multiplexing key bundles, regular Signal messages and the
        // very-first PreKeySignalMessage that bootstraps a session.
        val frame = BlueWaveFrame.decode(rawData)
        if (frame == null) {
            Log.w(TAG, "Dropping undecodable frame from $key (size=${rawData.size})")
            return
        }
        when (frame.type) {
            BlueWaveFrame.Type.KEY_BUNDLE -> handleIncomingKeyBundle(engine, key, frame.payload)
            BlueWaveFrame.Type.SIGNAL_MESSAGE -> handleIncomingSignalMessage(
                engine, key, senderName, frame.payload, isPreKey = false,
            )
            BlueWaveFrame.Type.PREKEY_SIGNAL_MESSAGE -> handleIncomingSignalMessage(
                engine, key, senderName, frame.payload, isPreKey = true,
            )
            BlueWaveFrame.Type.PROFILE_METADATA -> handleIncomingProfileMetadata(
                engine, key, frame.payload,
            )
        }
    }

    override suspend fun sendMessage(macAddress: String, plaintext: String) {
        val key = macAddress.uppercase()
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = cryptoManager.encrypt(plaintextBytes)
        // Persist the encrypted payload locally first — Single Source of
        // Truth: the UI subscribes to the DB and updates automatically
        // as soon as the row lands.
        messageDao.insertMessage(
            MessageEntity(
                macAddress = macAddress,
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = true,
                senderName = "Me",
                isRead = true,
            )
        )
        // Suppress radio writes when the peer is paused due to a
        // bond-loss event — the message stays in the local DB and will
        // be re-sent manually by the user once the link is restored.
        if (isPausedFor(macAddress)) return

        val activeTransport = transport ?: return
        val engine = signalEngine
        if (engine == null) {
            // Legacy plaintext-on-wire path: the transport length-prefixes
            // these bytes for free, no E2EE wrapping required.
            activeTransport.send(macAddress, plaintextBytes)
            return
        }

        // E2EE path: if we already have a Signal session for this
        // peer encrypt + ship now; otherwise queue the plaintext and
        // make sure our key bundle is in flight so the handshake can
        // complete and drain the queue.
        if (engine.hasSession(key)) {
            shipEncrypted(engine, activeTransport, key, plaintextBytes)
        } else {
            enqueuePending(key, plaintextBytes)
            sendLocalKeyBundleIfNeeded(key)
        }
    }

    override suspend fun deleteMessagesByDevice(macAddress: String) {
        messageDao.deleteMessagesByDevice(macAddress)
    }

    /**
     * Per-peer network state guard. `false` means we have observed a
     * `BluetoothDevice.ACTION_KEY_MISSING` for that MAC address (Android
     * 16 bond loss) and outgoing transmissions are suppressed until a
     * subsequent `ACTION_ENCRYPTION_CHANGE` flips it back via
     * [resumeNetworkOperations].
     *
     * Volatile-style synchronisation is sufficient here — writes only
     * happen on the BroadcastReceiver dispatch thread and reads are
     * cheap, so we use `@Synchronized` rather than `Mutex` to keep the
     * data layer free of coroutine plumbing for a single-bit flag.
     */
    private val pausedPeers: MutableSet<String> = mutableSetOf()

    override suspend fun pauseNetworkOperations(macAddress: String) {
        synchronized(pausedPeers) {
            pausedPeers.add(macAddress.uppercase())
        }
        // Tear down any live RFCOMM session for this peer so we don't
        // try to write into a socket that has lost its bond key. The
        // transport is idempotent — calling disconnect on an unknown
        // peer is a no-op.
        transport?.disconnect(macAddress)
    }

    override suspend fun resumeNetworkOperations(macAddress: String) {
        synchronized(pausedPeers) {
            pausedPeers.remove(macAddress.uppercase())
        }
    }

    /**
     * Visible for tests / step 31: returns whether the given peer is
     * currently paused due to ACTION_KEY_MISSING.
     */
    internal fun isPausedFor(macAddress: String): Boolean {
        return synchronized(pausedPeers) { macAddress.uppercase() in pausedPeers }
    }

    // ---------------------------------------------------------------
    // E2EE helpers
    // ---------------------------------------------------------------

    /**
     * Per-peer pending plaintext queue. Holds messages typed by the
     * local user before the libsignal session has been established;
     * drained by [drainPendingQueue] once we observe the peer's key
     * bundle.
     */
    private val pendingQueues: MutableMap<String, MutableList<ByteArray>> = HashMap()

    /** Roster of peers we have already pushed our [SignalEngine.localKeyBundle] to. */
    private val keyBundleSent: MutableSet<String> = HashSet()

    /** Per-peer reactive [E2EEState] surfaced to the UI. */
    private val sessionStates: MutableMap<String, MutableStateFlow<E2EEState>> = HashMap()

    /** Coarse mutex that protects every E2EE-related collaborator above. */
    private val e2eeLock: Mutex = Mutex()

    private fun sessionStateFor(macAddress: String): MutableStateFlow<E2EEState> {
        val key = macAddress.uppercase()
        return synchronized(sessionStates) {
            sessionStates.getOrPut(key) { MutableStateFlow(E2EEState.PENDING) }
        }
    }

    private suspend fun shipEncrypted(
        engine: SignalEngine,
        transport: MessageTransport,
        macAddress: String,
        plaintextBytes: ByteArray,
    ) {
        val ciphertext = try {
            engine.encrypt(macAddress, plaintextBytes)
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Encrypt failed for $macAddress: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        val frameType = when (ciphertext.type) {
            SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE -> BlueWaveFrame.Type.SIGNAL_MESSAGE
            SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE -> BlueWaveFrame.Type.PREKEY_SIGNAL_MESSAGE
        }
        val framed = BlueWaveFrame.encode(frameType, ciphertext.bytes)
        transport.send(macAddress, framed)
        sessionStateFor(macAddress).value = E2EEState.SECURE
    }

    private suspend fun enqueuePending(macAddress: String, plaintext: ByteArray) {
        e2eeLock.withLock {
            pendingQueues.getOrPut(macAddress) { ArrayList() }.add(plaintext)
        }
        sessionStateFor(macAddress).value = E2EEState.PENDING
    }

    private suspend fun sendLocalKeyBundleIfNeeded(macAddress: String) {
        val engine = signalEngine ?: return
        val activeTransport = transport ?: return
        val alreadySent = e2eeLock.withLock { !keyBundleSent.add(macAddress) }
        if (alreadySent) return
        val bundle = try {
            engine.localKeyBundle()
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Local key bundle generation failed: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        val framed = BlueWaveFrame.encode(BlueWaveFrame.Type.KEY_BUNDLE, bundle)
        activeTransport.send(macAddress, framed)
    }

    private suspend fun handleIncomingKeyBundle(
        engine: SignalEngine,
        macAddress: String,
        bundle: ByteArray,
    ) {
        try {
            engine.processPeerKeyBundle(macAddress, bundle)
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Failed to process peer key bundle from $macAddress: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        // The peer expects us to be reachable too — make sure our
        // own key bundle is in flight (no-op if we already sent it).
        sendLocalKeyBundleIfNeeded(macAddress)
        drainPendingQueue(engine, macAddress)
        sessionStateFor(macAddress).value = E2EEState.SECURE
        pushLocalProfileIfReady(engine, macAddress)
    }

    private suspend fun handleIncomingSignalMessage(
        engine: SignalEngine,
        macAddress: String,
        senderName: String,
        ciphertext: ByteArray,
        isPreKey: Boolean,
    ) {
        val plaintext = try {
            if (isPreKey) {
                engine.decryptPreKeyMessage(macAddress, ciphertext)
            } else {
                engine.decryptSignalMessage(macAddress, ciphertext)
            }
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Decrypt failed for $macAddress: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        sessionStateFor(macAddress).value = E2EEState.SECURE
        // A successfully decrypted PreKeySignalMessage means
        // libsignal already built our receiving session as a side
        // effect; no extra bookkeeping required. Drain any local
        // outbound that was waiting on a session.
        if (isPreKey) {
            drainPendingQueue(engine, macAddress)
            pushLocalProfileIfReady(engine, macAddress)
        }
        persistInboundPlaintext(macAddress, senderName, plaintext)
    }

    /**
     * Decrypts a `PROFILE_METADATA` frame and upserts the resulting
     * card into the [peerProfileDao] cache. Tolerant of malformed
     * payloads — anything that fails to decode is logged and dropped
     * so a single bad frame can never poison the local DB.
     */
    private suspend fun handleIncomingProfileMetadata(
        engine: SignalEngine,
        macAddress: String,
        body: ByteArray,
    ) {
        val dao = peerProfileDao ?: return
        val inner = BlueWaveFrame.ProfileEnvelope.decode(body)
        if (inner == null) {
            Log.w(TAG, "Dropping malformed profile envelope from $macAddress (size=${body.size})")
            return
        }
        val plaintext = try {
            when (inner.subtype) {
                BlueWaveFrame.ProfileEnvelope.Subtype.SIGNAL_MESSAGE ->
                    engine.decryptSignalMessage(macAddress, inner.ciphertext)
                BlueWaveFrame.ProfileEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE ->
                    engine.decryptPreKeyMessage(macAddress, inner.ciphertext)
            }
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Failed to decrypt profile metadata from $macAddress: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        sessionStateFor(macAddress).value = E2EEState.SECURE
        if (inner.subtype == BlueWaveFrame.ProfileEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE) {
            // Side-effect of libsignal building the receiving session
            // — drain any queued plaintext now that we can encrypt it.
            drainPendingQueue(engine, macAddress)
            pushLocalProfileIfReady(engine, macAddress)
        }
        val profile = LocalProfileCodec.decode(plaintext)
        if (profile == null) {
            Log.w(TAG, "Dropping malformed profile JSON from $macAddress")
            return
        }
        dao.upsert(
            PeerProfileEntity(
                macAddress = macAddress,
                displayName = profile.displayName,
                handle = profile.handle,
                bio = profile.bio,
                avatarUri = profile.avatarUri,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Pushes the local user's profile card to [macAddress] iff the
     * Signal session is established and a peer-profile cache is wired
     * up (the latter is the only consumer of the inbound side, so
     * skipping the push when no cache exists keeps tests honest).
     *
     * Safe to call multiple times — the wire frame is idempotent at
     * the receiver thanks to the `peer_profile` PK on macAddress.
     */
    private suspend fun pushLocalProfileIfReady(
        engine: SignalEngine,
        macAddress: String,
    ) {
        val activeTransport = transport ?: return
        if (peerProfileDao == null) return
        val profile = runCatching { localProfileProvider() }.getOrNull() ?: return
        val payload = LocalProfileCodec.encode(profile)
        shipProfilePayload(engine, activeTransport, macAddress, payload)
    }

    /**
     * Encrypts [payload] with the libsignal session for [macAddress]
     * and ships it inside a `PROFILE_METADATA` frame. The inner
     * envelope echoes the libsignal ciphertext type so the receiver
     * can pick the right decrypt path without re-inspecting the wire
     * bytes.
     */
    private suspend fun shipProfilePayload(
        engine: SignalEngine,
        transport: MessageTransport,
        macAddress: String,
        payload: ByteArray,
    ) {
        val ciphertext = try {
            engine.encrypt(macAddress, payload)
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Profile encrypt failed for $macAddress: ${e.message}")
            return
        }
        val subtype = when (ciphertext.type) {
            SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE ->
                BlueWaveFrame.ProfileEnvelope.Subtype.SIGNAL_MESSAGE
            SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE ->
                BlueWaveFrame.ProfileEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE
        }
        val body = BlueWaveFrame.ProfileEnvelope.encode(subtype, ciphertext.bytes)
        val framed = BlueWaveFrame.encode(BlueWaveFrame.Type.PROFILE_METADATA, body)
        transport.send(macAddress, framed)
    }

    private suspend fun drainPendingQueue(engine: SignalEngine, macAddress: String) {
        val activeTransport = transport ?: return
        val toShip: List<ByteArray> = e2eeLock.withLock {
            val pending = pendingQueues.remove(macAddress) ?: return@withLock emptyList()
            pending.toList()
        }
        for (plaintext in toShip) {
            shipEncrypted(engine, activeTransport, macAddress, plaintext)
        }
    }

    private suspend fun persistInboundPlaintext(
        macAddress: String,
        senderName: String,
        plaintext: ByteArray,
    ) {
        val (iv, ciphertext) = cryptoManager.encrypt(plaintext)
        messageDao.insertMessage(
            MessageEntity(
                macAddress = macAddress,
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = false,
                senderName = senderName,
            )
        )
    }

    private companion object {
        const val TAG = "MessageRepository"
    }
}

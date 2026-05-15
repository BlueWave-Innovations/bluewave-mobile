package com.example.bluewave_mobile.data

import com.example.bluewave_mobile.utils.BlueWaveLogger
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.SignalEngine
import com.example.bluewave_mobile.crypto.SignalEngineException
import com.example.bluewave_mobile.network.BlueWaveFrame
import com.example.bluewave_mobile.network.MessageTransport
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.preferences.LocalProfileCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
    private val groupRepository: GroupRepository? = null,
    private val mediaStorageDir: java.io.File? = null,
    private val appContext: android.content.Context? = null,
) : MessageRepository {

    @Volatile
    override var onMessageReceived: ((String, String, String) -> Unit)? = null

    private val typingTimestamps: MutableMap<String, Long> = mutableMapOf()
    private val typingFlows: MutableMap<String, kotlinx.coroutines.flow.MutableStateFlow<Boolean>> = mutableMapOf()

    /** Version stamp of the latest local profile (bumped on every edit). */
    private var latestProfileVersion: Long = System.currentTimeMillis()

    /** Per-peer last-ACKed profile version. Guarded by its own lock. */
    private val profileAckVersions: MutableMap<String, Long> = mutableMapOf()

    /** Per-peer last-sent profile version. Guarded by its own lock. */
    private val lastSentProfileVersion: MutableMap<String, Long> = mutableMapOf()

    override suspend fun sendTyping(macAddress: String) {
        val key = macAddress.uppercase()
        val activeTransport = transport ?: return
        if (isPausedFor(key)) return
        val frame = BlueWaveFrame.encode(BlueWaveFrame.Type.TYPING_INDICATOR, ByteArray(0))
        withContext(Dispatchers.IO) {
            runCatching {
                activeTransport.send(key, frame)
            }.onFailure { e ->
                BlueWaveLogger.w(TAG, "sendTyping failed for $key: ${e.message}")
            }
        }
    }

    override fun observePeerTyping(macAddress: String): kotlinx.coroutines.flow.Flow<Boolean> {
        val key = macAddress.uppercase()
        return synchronized(typingFlows) {
            typingFlows.getOrPut(key) { kotlinx.coroutines.flow.MutableStateFlow(false) }
        }
    }

    private fun markPeerTyping(macAddress: String) {
        val key = macAddress.uppercase()
        val now = System.currentTimeMillis()
        synchronized(typingTimestamps) { typingTimestamps[key] = now }
        synchronized(typingFlows) {
            typingFlows[key]?.value = true
        }
        // Auto-reset after 3 seconds.
        GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            delay(3_000L)
            synchronized(typingTimestamps) {
                if (typingTimestamps[key] == now) {
                    typingTimestamps.remove(key)
                    synchronized(typingFlows) {
                        typingFlows[key]?.value = false
                    }
                }
            }
        }
    }

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
        latestProfileVersion = System.currentTimeMillis()
        val payload = LocalProfileCodec.encode(profile, appContext, latestProfileVersion)
        for (peer in peers) {
            shipProfilePayload(engine, activeTransport, peer, payload, latestProfileVersion)
        }
    }

    override suspend fun syncProfilesToConnectedPeers() {
        val engine = signalEngine ?: return
        val activeTransport = transport ?: return
        val peers: List<String> = synchronized(sessionStates) {
            sessionStates.entries.filter { it.value.value == E2EEState.SECURE }.map { it.key }
        }
        if (peers.isEmpty()) return
        val profile = runCatching { localProfileProvider() }.getOrNull() ?: return
        val payload = LocalProfileCodec.encode(profile, appContext, latestProfileVersion)
        for (peer in peers) {
            val acked = synchronized(profileAckVersions) { profileAckVersions[peer] ?: 0L }
            if (acked != latestProfileVersion) {
                shipProfilePayload(engine, activeTransport, peer, payload, latestProfileVersion)
            }
        }
    }

    override suspend fun onPeerLinkUp(macAddress: String) {
        // Eager handshake: as soon as the radio link is up, push our
        // local key bundle so the peer can derive its sending session.
        // This is symmetric — whichever side initiated the connect,
        // both peers fire this hook on attach.
        sendLocalKeyBundleIfNeeded(macAddress.uppercase())
    }

    override suspend fun onPeerLinkDown(macAddress: String) {
        val key = macAddress.uppercase()
        // Drop the libsignal session record so the next link-up
        // rebuilds the Double Ratchet from scratch. Without this the
        // peer-side ratchet (which the peer may have lost — fresh
        // process with an in-memory store) and our ratchet would
        // disagree on the message counter and decrypt would fail
        // silently for every subsequent message.
        signalEngine?.resetPeerSession(key)
        e2eeLock.withLock {
            // Forget that we already shipped a key bundle so the next
            // [onPeerLinkUp] re-sends it; also drop any plaintext that
            // was waiting on the dead session — the user will retype
            // if they cared (queued bytes encrypted with a session the
            // peer no longer has are unrecoverable anyway).
            keyBundleSent.remove(key)
            pendingQueues.remove(key)
        }
        // Surface "handshake pending" so the chat header shows the
        // spinner until the next link-up settles. The state flow is
        // observed by the UI so this also flips the online dot off
        // via downstream session-collection in `BluetoothSessionManager`.
        sessionStateFor(key).value = E2EEState.PENDING
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
            BlueWaveLogger.w(TAG, "Dropping undecodable frame from $key (size=${rawData.size})")
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
            BlueWaveFrame.Type.GROUP_INVITE -> handleIncomingGroupFrame(
                engine, key, senderName, frame.payload, isInvite = true,
            )
            BlueWaveFrame.Type.GROUP_MESSAGE -> handleIncomingGroupFrame(
                engine, key, senderName, frame.payload, isInvite = false,
            )
            BlueWaveFrame.Type.HEARTBEAT -> {
                // Heartbeats are transport-internal — silently dropped.
            }
            BlueWaveFrame.Type.MESSAGE_ACK -> handleIncomingAck(engine, key, frame.payload)
            BlueWaveFrame.Type.MEDIA_MESSAGE -> handleIncomingMediaMessage(key, frame.payload)
            BlueWaveFrame.Type.TYPING_INDICATOR -> markPeerTyping(key)
            BlueWaveFrame.Type.PROFILE_ACK -> handleIncomingProfileAck(key, frame.payload)
        }
    }

    override suspend fun sendMessage(macAddress: String, plaintext: String) {
        val key = macAddress.uppercase()
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = cryptoManager.encrypt(plaintextBytes)
        val uuid = UUID.randomUUID().toString()
        messageDao.insertMessage(
            MessageEntity(
                macAddress = key,
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = true,
                senderName = "Me",
                isRead = true,
                deliveryStatus = MessageEntity.STATUS_SENT,
                messageUuid = uuid,
            )
        )
        // Suppress radio writes when the peer is paused due to a
        // bond-loss event — the message stays in the local DB and will
        // be re-sent manually by the user once the link is restored.
        if (isPausedFor(key)) return

        val activeTransport = transport ?: return

        withContext(Dispatchers.IO) {
            // Make sure we have an RFCOMM session before we try to ship
            // anything. `connect` is idempotent on the manager side (it
            // early-returns when a session for this MAC already exists)
            // and on the transport's accept loop side (the symmetric
            // peer's listener will be reused), so this is effectively
            // a no-op on the happy path.
            if (!activeTransport.isConnected(key)) {
                runCatching { activeTransport.connect(key) }
                    .onFailure { e ->
                        BlueWaveLogger.w(TAG, "send-side connect attempt 1 failed for $key: ${e.message}")
                    }
            }
            if (!activeTransport.isConnected(key)) {
                delay(500L)
                runCatching { activeTransport.connect(key) }
                    .onFailure { e ->
                        BlueWaveLogger.w(TAG, "send-side connect attempt 2 failed for $key: ${e.message}")
                    }
            }

            // Prepend the message UUID so the receiver can ACK it.
            val wirePayload = (uuid + "\n").toByteArray(Charsets.UTF_8) + plaintextBytes

            val engine = signalEngine
            if (engine == null) {
                activeTransport.send(key, wirePayload)
                return@withContext
            }

            if (engine.hasSession(key)) {
                shipEncrypted(engine, activeTransport, key, wirePayload)
            } else {
                enqueuePending(key, wirePayload)
                sendLocalKeyBundleIfNeeded(key)
            }
        }
    }

    override suspend fun sendMediaMessage(
        macAddress: String,
        attachmentName: String,
        mimeType: String,
        localPath: String,
    ) {
        val key = macAddress.uppercase()
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile) {
            BlueWaveLogger.w(TAG, "sendMediaMessage: file not found at $localPath")
            return
        }
        if (file.length() > MAX_MEDIA_BYTES) {
            BlueWaveLogger.w(TAG, "sendMediaMessage: file too large (${file.length()} bytes)")
            return
        }
        val fileBytes = withContext(Dispatchers.IO) { file.readBytes() }
        val uuid = UUID.randomUUID().toString()
        messageDao.insertMessage(
            MessageEntity(
                macAddress = key,
                encryptedPayload = ByteArray(0),
                iv = ByteArray(0),
                isOutgoing = true,
                senderName = "Me",
                isRead = true,
                deliveryStatus = MessageEntity.STATUS_SENT,
                messageUuid = uuid,
                attachmentPath = localPath,
                attachmentName = attachmentName,
                attachmentMimeType = mimeType,
                attachmentSize = file.length(),
                transferStatus = MessageEntity.TRANSFER_UPLOADING,
            )
        )
        if (isPausedFor(key)) return
        val activeTransport = transport ?: return
        withContext(Dispatchers.IO) {
            if (!activeTransport.isConnected(key)) {
                runCatching { activeTransport.connect(key) }
                    .onFailure { e -> BlueWaveLogger.w(TAG, "media connect attempt 1 failed for $key: ${e.message}") }
            }
            if (!activeTransport.isConnected(key)) {
                delay(500L)
                runCatching { activeTransport.connect(key) }
                    .onFailure { e -> BlueWaveLogger.w(TAG, "media connect attempt 2 failed for $key: ${e.message}") }
            }
            val payload = BlueWaveFrame.MediaPayload.encode(
                name = attachmentName,
                mimeType = mimeType,
                size = file.length(),
                uuid = uuid,
                bytes = fileBytes,
            )
            val engine = signalEngine
            if (engine == null) {
                val ok = activeTransport.send(key, BlueWaveFrame.encode(BlueWaveFrame.Type.MEDIA_MESSAGE, payload))
                messageDao.updateTransferStatus(
                    uuid,
                    if (ok) MessageEntity.TRANSFER_COMPLETED else MessageEntity.TRANSFER_FAILED,
                )
                return@withContext
            }
            if (engine.hasSession(key)) {
                val ciphertext = try {
                    engine.encrypt(key, payload)
                } catch (e: SignalEngineException) {
                    BlueWaveLogger.w(TAG, "Media encrypt failed for $key: ${e.message}")
                    sessionStateFor(key).value = E2EEState.FAILED
                    return@withContext
                }
                val subtype = when (ciphertext.type) {
                    SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE ->
                        BlueWaveFrame.MediaEnvelope.Subtype.SIGNAL_MESSAGE
                    SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE ->
                        BlueWaveFrame.MediaEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE
                }
                val body = BlueWaveFrame.MediaEnvelope.encode(subtype, ciphertext.bytes)
                val framed = BlueWaveFrame.encode(BlueWaveFrame.Type.MEDIA_MESSAGE, body)
                val ok = activeTransport.send(key, framed)
                messageDao.updateTransferStatus(
                    uuid,
                    if (ok) MessageEntity.TRANSFER_COMPLETED else MessageEntity.TRANSFER_FAILED,
                )
                sessionStateFor(key).value = E2EEState.SECURE
            } else {
                enqueuePending(key, payload)
                sendLocalKeyBundleIfNeeded(key)
            }
        }
    }

    override suspend fun deleteMessagesByDevice(macAddress: String) {
        messageDao.deleteMessagesByDevice(macAddress)
    }

    override suspend fun deleteMessageById(id: Long) {
        messageDao.deleteMessageById(id)
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
            BlueWaveLogger.w(TAG, "Encrypt failed for $macAddress: ${e.message}")
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
            BlueWaveLogger.w(TAG, "Local key bundle generation failed: ${e.message}")
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
            BlueWaveLogger.w(TAG, "Failed to process peer key bundle from $macAddress: ${e.message}")
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
            BlueWaveLogger.w(TAG, "Decrypt failed for $macAddress: ${e.message}")
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
     * Decrypts a `GROUP_INVITE` or `GROUP_MESSAGE` frame and routes
     * the resulting plaintext to [groupRepository].
     *
     * The wire envelope is identical to `PROFILE_METADATA` — a
     * one-byte subtype tag followed by a libsignal ciphertext. The
     * subtype tells us whether to call `decryptSignalMessage` or
     * `decryptPreKeyMessage`; the inner plaintext format depends on
     * [isInvite] and is parsed downstream by the group repository.
     */
    private suspend fun handleIncomingGroupFrame(
        engine: SignalEngine,
        macAddress: String,
        senderName: String,
        body: ByteArray,
        isInvite: Boolean,
    ) {
        val groups = groupRepository
        if (groups == null) {
            BlueWaveLogger.w(TAG, "Dropping group frame from $macAddress — no GroupRepository wired")
            return
        }
        val inner = BlueWaveFrame.GroupEnvelope.decode(body)
        if (inner == null) {
            BlueWaveLogger.w(TAG, "Dropping malformed group envelope from $macAddress (size=${body.size})")
            return
        }
        val plaintext = try {
            when (inner.subtype) {
                BlueWaveFrame.GroupEnvelope.Subtype.SIGNAL_MESSAGE ->
                    engine.decryptSignalMessage(macAddress, inner.ciphertext)
                BlueWaveFrame.GroupEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE ->
                    engine.decryptPreKeyMessage(macAddress, inner.ciphertext)
            }
        } catch (e: SignalEngineException) {
            BlueWaveLogger.w(TAG, "Group decrypt failed for $macAddress: ${e.message}")
            sessionStateFor(macAddress).value = E2EEState.FAILED
            return
        }
        sessionStateFor(macAddress).value = E2EEState.SECURE
        if (inner.subtype == BlueWaveFrame.GroupEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE) {
            // The peer's PreKeySignalMessage built our receiving session
            // as a side effect — flush any queued chat / profile / group
            // ops that were waiting on the handshake.
            drainPendingQueue(engine, macAddress)
            pushLocalProfileIfReady(engine, macAddress)
            groups.onPeerSessionSecure(macAddress)
        }
        if (isInvite) {
            groups.handleIncomingGroupInvite(macAddress, plaintext)
        } else {
            groups.handleIncomingGroupMessage(macAddress, senderName, plaintext)
        }
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
            BlueWaveLogger.w(TAG, "Dropping malformed profile envelope from $macAddress (size=${body.size})")
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
            BlueWaveLogger.w(TAG, "Failed to decrypt profile metadata from $macAddress: ${e.message}")
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
        val avatarDir = mediaStorageDir?.let { File(it, "avatars") }
        avatarDir?.mkdirs()
        val destAvatarFile = avatarDir?.let { File(it, "${macAddress}_avatar.jpg") }
        val decoded = LocalProfileCodec.decode(plaintext, destAvatarFile)
        if (decoded == null) {
            BlueWaveLogger.w(TAG, "Dropping malformed profile JSON from $macAddress")
            return
        }
        val (profile, version) = decoded
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
        // Acknowledge receipt so the sender knows this profile arrived.
        sendProfileAck(macAddress, version)
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
        val payload = LocalProfileCodec.encode(profile, appContext, latestProfileVersion)
        shipProfilePayload(engine, activeTransport, macAddress, payload, latestProfileVersion)
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
        version: Long,
    ) {
        val ciphertext = try {
            engine.encrypt(macAddress, payload)
        } catch (e: SignalEngineException) {
            BlueWaveLogger.w(TAG, "Profile encrypt failed for $macAddress: ${e.message}")
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
        val key = macAddress.uppercase()
        synchronized(lastSentProfileVersion) { lastSentProfileVersion[key] = version }
    }

    private suspend fun sendProfileAck(macAddress: String, version: Long) {
        val activeTransport = transport ?: return
        val payload = ByteArray(8).apply {
            this[0] = ((version ushr 56) and 0xFF).toByte()
            this[1] = ((version ushr 48) and 0xFF).toByte()
            this[2] = ((version ushr 40) and 0xFF).toByte()
            this[3] = ((version ushr 32) and 0xFF).toByte()
            this[4] = ((version ushr 24) and 0xFF).toByte()
            this[5] = ((version ushr 16) and 0xFF).toByte()
            this[6] = ((version ushr 8) and 0xFF).toByte()
            this[7] = (version and 0xFF).toByte()
        }
        val ackFrame = BlueWaveFrame.encode(BlueWaveFrame.Type.PROFILE_ACK, payload)
        activeTransport.send(macAddress, ackFrame)
    }

    private fun handleIncomingProfileAck(macAddress: String, body: ByteArray) {
        if (body.size < 8) return
        val version = (
            (body[0].toInt() and 0xFF).toLong() shl 56 or
                (body[1].toInt() and 0xFF).toLong() shl 48 or
                (body[2].toInt() and 0xFF).toLong() shl 40 or
                (body[3].toInt() and 0xFF).toLong() shl 32 or
                (body[4].toInt() and 0xFF).toLong() shl 24 or
                (body[5].toInt() and 0xFF).toLong() shl 16 or
                (body[6].toInt() and 0xFF).toLong() shl 8 or
                (body[7].toInt() and 0xFF).toLong()
            )
        val key = macAddress.uppercase()
        synchronized(profileAckVersions) { profileAckVersions[key] = version }
        BlueWaveLogger.d(TAG, "Profile ACK from $key for version $version")
    }

    private suspend fun drainPendingQueue(engine: SignalEngine, macAddress: String) {
        val activeTransport = transport ?: return
        val toShip: List<ByteArray> = e2eeLock.withLock {
            val pending = pendingQueues.remove(macAddress) ?: return@withLock emptyList()
            pending.toList()
        }
        for (plaintext in toShip) {
            runCatching {
                shipEncrypted(engine, activeTransport, macAddress, plaintext)
            }.onFailure { e ->
                BlueWaveLogger.w(TAG, "Failed to ship pending message to $macAddress: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingMediaMessage(
        macAddress: String,
        payload: ByteArray,
    ) {
        val dir = mediaStorageDir ?: return
        val engine = signalEngine
        val fileBytes: ByteArray
        val meta: BlueWaveFrame.MediaPayload.Inner
        if (engine != null) {
            val envelope = BlueWaveFrame.MediaEnvelope.decode(payload)
            if (envelope == null) {
                BlueWaveLogger.w(TAG, "Dropping malformed encrypted MEDIA_MESSAGE from $macAddress")
                return
            }
            val decrypted = try {
                when (envelope.subtype) {
                    BlueWaveFrame.MediaEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE ->
                        engine.decryptPreKeyMessage(macAddress, envelope.ciphertext)
                    BlueWaveFrame.MediaEnvelope.Subtype.SIGNAL_MESSAGE ->
                        engine.decryptSignalMessage(macAddress, envelope.ciphertext)
                }
            } catch (e: SignalEngineException) {
                BlueWaveLogger.w(TAG, "Media decrypt failed for $macAddress: ${e.message}")
                sessionStateFor(macAddress).value = E2EEState.FAILED
                return
            }
            sessionStateFor(macAddress).value = E2EEState.SECURE
            if (envelope.subtype == BlueWaveFrame.MediaEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE) {
                drainPendingQueue(engine, macAddress)
                pushLocalProfileIfReady(engine, macAddress)
            }
            val decryptedPayload = BlueWaveFrame.MediaPayload.decode(decrypted)
            if (decryptedPayload == null) {
                BlueWaveLogger.w(TAG, "Dropping malformed inner MEDIA_MESSAGE from $macAddress")
                return
            }
            fileBytes = decryptedPayload.bytes
            meta = decryptedPayload
        } else {
            val inner = BlueWaveFrame.MediaPayload.decode(payload)
            if (inner == null) {
                BlueWaveLogger.w(TAG, "Dropping malformed MEDIA_MESSAGE from $macAddress")
                return
            }
            fileBytes = inner.bytes
            meta = inner
        }
        val safeName = meta.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val destDir = java.io.File(dir, "inbound").apply { mkdirs() }
        val destFile = java.io.File(destDir, "${meta.uuid}_$safeName")
        withContext(Dispatchers.IO) {
            destFile.writeBytes(fileBytes)
        }
        messageDao.insertMessage(
            MessageEntity(
                macAddress = macAddress.uppercase(),
                encryptedPayload = ByteArray(0),
                iv = ByteArray(0),
                isOutgoing = false,
                senderName = "",
                messageUuid = meta.uuid,
                attachmentPath = destFile.absolutePath,
                attachmentName = meta.name,
                attachmentMimeType = meta.mimeType,
                attachmentSize = meta.size,
                transferStatus = MessageEntity.TRANSFER_COMPLETED,
            )
        )
        onMessageReceived?.invoke(macAddress, "", meta.name)
    }

    private suspend fun persistInboundPlaintext(
        macAddress: String,
        senderName: String,
        plaintext: ByteArray,
    ) {
        // Extract the UUID prefix if present: 36 ASCII bytes + '\n' + message.
        // We work with raw bytes so multi-byte UTF-8 characters in the
        // message body cannot confuse a String-based indexOf search.
        val (messageUuid, messageText) = extractUuidAndText(plaintext)

        val messageBytes = messageText.toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = cryptoManager.encrypt(messageBytes)
        messageDao.insertMessage(
            MessageEntity(
                macAddress = macAddress.uppercase(),
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = false,
                senderName = senderName,
                messageUuid = messageUuid,
            )
        )

        // Send delivery ACK back to the sender.
        if (messageUuid.isNotBlank()) {
            sendAckForMessage(macAddress.uppercase(), messageUuid)
        }

        onMessageReceived?.invoke(macAddress, senderName, messageText)
    }

    /**
     * Splits [plaintext] into a (UUID, text) pair.
     * The wire format is exactly 36 US-ASCII UUID bytes, one '\n' byte,
     * then arbitrary UTF-8 message bytes. Anything else yields ("", full).
     */
    private fun extractUuidAndText(plaintext: ByteArray): Pair<String, String> {
        if (plaintext.size >= UUID_LENGTH + 1 && plaintext[UUID_LENGTH] == '\n'.code.toByte()) {
            val uuid = String(plaintext, 0, UUID_LENGTH, Charsets.US_ASCII)
            val text = String(plaintext, UUID_LENGTH + 1, plaintext.size - UUID_LENGTH - 1, Charsets.UTF_8)
            return uuid to text
        }
        return "" to String(plaintext, Charsets.UTF_8)
    }

    private suspend fun sendAckForMessage(macAddress: String, messageUuid: String) {
        val activeTransport = transport ?: return
        val payload = messageUuid.toByteArray(Charsets.UTF_8)
        val engine = signalEngine
        if (engine != null && engine.hasSession(macAddress)) {
            val ct = try {
                engine.encrypt(macAddress, payload)
            } catch (e: SignalEngineException) {
                BlueWaveLogger.w(TAG, "ACK encrypt failed for $macAddress")
                return
            }
            val frameType = when (ct.type) {
                SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE -> BlueWaveFrame.Type.SIGNAL_MESSAGE
                SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE -> BlueWaveFrame.Type.PREKEY_SIGNAL_MESSAGE
            }
            val innerFramed = BlueWaveFrame.encode(frameType, ct.bytes)
            val ackFrame = BlueWaveFrame.encode(BlueWaveFrame.Type.MESSAGE_ACK, innerFramed)
            activeTransport.send(macAddress, ackFrame)
        } else {
            val ackFrame = BlueWaveFrame.encode(BlueWaveFrame.Type.MESSAGE_ACK, payload)
            activeTransport.send(macAddress, ackFrame)
        }
    }

    private suspend fun handleIncomingAck(
        engine: SignalEngine,
        macAddress: String,
        body: ByteArray,
    ) {
        // The ACK payload may be:
        //  * plaintext UUID (legacy / no E2EE) — 36 ASCII bytes
        //  * an inner BlueWaveFrame wrapping the encrypted UUID
        //    (sent by sendAckForMessage when a Signal session exists)
        val inner = BlueWaveFrame.decode(body)
        val plaintext: ByteArray = if (inner != null) {
            val decrypted = try {
                when (inner.type) {
                    BlueWaveFrame.Type.PREKEY_SIGNAL_MESSAGE ->
                        engine.decryptPreKeyMessage(macAddress, inner.payload)
                    BlueWaveFrame.Type.SIGNAL_MESSAGE ->
                        engine.decryptSignalMessage(macAddress, inner.payload)
                    else -> null
                }
            } catch (e: SignalEngineException) {
                BlueWaveLogger.w(TAG, "ACK decrypt failed for $macAddress: ${e.message}")
                null
            }
            if (decrypted != null) {
                decrypted
            } else {
                // Encrypted frame that failed to decrypt — do NOT fall back
                // to treating raw bytes as plaintext (avoids decryption oracle).
                return
            }
        } else {
            body
        }
        val uuid = String(plaintext, Charsets.UTF_8).trim()
        if (uuid.isNotBlank() && uuid.length == UUID_LENGTH) {
            messageDao.updateDeliveryStatus(uuid, MessageEntity.STATUS_DELIVERED)
        }
    }

    private companion object {
        const val TAG = "MessageRepository"
        const val UUID_LENGTH = 36
        /** Hard ceiling for media files shipped in a single MEDIA_MESSAGE frame (3.5 MiB). */
        const val MAX_MEDIA_BYTES = 3_500_000L
    }
}

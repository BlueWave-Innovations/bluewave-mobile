package com.example.bluewave_mobile.data

import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.network.MessageTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * @property messageDao The Room DAO for message CRUD operations.
 * @property cryptoManager AES-256-GCM facade used to encrypt messages
 *                          for at-rest storage in the local database.
 * @property transport     Optional [MessageTransport] used to push
 *                          plaintext bytes to the peer over RFCOMM.
 *                          Defaults to `null` so unit tests can run
 *                          without a Bluetooth radio.
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao,
    private val cryptoManager: CryptoManager = CryptoManager(),
    private val transport: MessageTransport? = null,
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

    override suspend fun insertMessage(message: MessageEntity): Long {
        return messageDao.insertMessage(message)
    }

    override suspend fun processIncomingMessage(
        macAddress: String,
        senderName: String,
        rawData: ByteArray
    ) {
        // [rawData] is the plaintext UTF-8 payload of one fully
        // reassembled BlueWave wire frame (the length prefix has
        // already been stripped by the network layer's
        // FrameAccumulator). We re-encrypt it with the local AES key
        // for encryption-at-rest so the on-disk shape stays identical
        // to outgoing messages and the UI's render-time decrypt path
        // works without branching.
        val (iv, ciphertext) = cryptoManager.encrypt(rawData)
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

    override suspend fun sendMessage(macAddress: String, plaintext: String) {
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
                senderName = "Me"
            )
        )
        // Suppress radio writes when the peer is paused due to a
        // bond-loss event — the message stays in the local DB and will
        // be re-sent manually by the user once the link is restored.
        if (isPausedFor(macAddress)) return

        // Just-in-time connect. The auto-connect fan-out on app
        // launch (BlueWaveApplication.connectToBondedPeers) only
        // covers already-bonded peers, and even for bonded peers it
        // may not have completed by the time the user fires off the
        // very first message. Without this check `transport.send`
        // returns false (no live session) and the message is
        // silently dropped — the user sees their own bubble and
        // assumes everything worked while the peer never receives
        // the bytes. The call is idempotent: when a session already
        // exists, `connect` returns immediately.
        val t = transport
        if (t != null && !t.isConnected(macAddress)) {
            runCatching { t.connect(macAddress) }
        }

        // Hand the plaintext bytes to the transport. The transport
        // owns the framing (length-prefix wire format) and the
        // per-peer BluetoothSession; if it returns false the session
        // has gone stale and the user can retry from the chat input.
        transport?.send(macAddress, plaintextBytes)
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
}

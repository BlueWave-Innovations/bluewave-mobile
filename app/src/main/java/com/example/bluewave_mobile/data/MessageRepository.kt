package com.example.bluewave_mobile.data

import kotlinx.coroutines.flow.Flow

/**
 * Single Source of Truth for everything message-related.
 *
 * BlueWave is a peer-to-peer Bluetooth messenger and the data flows in
 * **two** directions through this repository:
 *
 *  * **Outbound** — UI calls [sendMessage] with a plaintext string. The
 *    implementation encrypts it with AES-256-GCM (a fresh IV per call,
 *    see [com.example.bluewave_mobile.crypto.CryptoManager]) and persists
 *    the resulting ciphertext into the Room database **before** any
 *    radio transmission so the UI subscribes to [getMessagesByDevice] /
 *    [getLatestMessagePerDevice] and is updated automatically through
 *    Room's invalidation tracker once the row lands. The plaintext UTF-8
 *    bytes are then handed to the network layer
 *    ([com.example.bluewave_mobile.network.MessageTransport]) which
 *    frames them with the BlueWave length-prefix wire format and writes
 *    them to the peer's `BluetoothSocket`. The interface intentionally
 *    does not expose any network primitives so the ViewModels never
 *    take a hard dependency on `android.bluetooth`.
 *
 *  * **Inbound** — the network layer hands plaintext UTF-8 bytes from a
 *    fully-reassembled frame to [processIncomingMessage]. The
 *    implementation re-encrypts them with the local AES-256-GCM key for
 *    encryption-at-rest and inserts the resulting [MessageEntity] into
 *    Room. Note that BlueWave's e2e wire format is plaintext on top of
 *    classic Bluetooth's link-level encryption — the local AES key is
 *    used only for at-rest storage and is **not** shared between peers.
 *
 * **Structured Concurrency.** Every `suspend` function on this
 * interface MUST be cancellable. Implementations should rely on Room's
 * built-in cancellation support and NOT block the calling coroutine on
 * I/O — the UI layer composes these calls via
 * `viewModelScope.launch { … }` and a hung repository call would freeze
 * the chat screen.
 *
 * **Android 16 bond loss.** Android 16 introduced
 * `BluetoothDevice.ACTION_KEY_MISSING` /
 * `BluetoothDevice.ACTION_ENCRYPTION_CHANGE` lifecycle events. The
 * repository is the single owner of the per-peer paused-state set
 * (see [pauseNetworkOperations] / [resumeNetworkOperations]) so the
 * UI layer can stay completely oblivious to the radio link state.
 *
 * The interface is the public contract; ViewModels and tests MUST
 * depend on it rather than on [MessageRepositoryImpl].
 */
interface MessageRepository {

    /**
     * Retrieves the chat history for a specific Bluetooth device as a reactive stream.
     *
     * @param macAddress MAC address of the remote device.
     * @return A [Flow] emitting an updated list of messages whenever the database changes.
     */
    fun getMessagesByDevice(macAddress: String): Flow<List<MessageEntity>>

    /**
     * Returns one [ConversationSummary] per peer with whom there is at
     * least one persisted message, ordered by the timestamp of the last
     * message (newest first).
     *
     * The device-list screen consumes this flow to populate the
     * "Chats" section of its sectioned contact list. The unread count
     * is computed from inbound rows where `isRead = false` — see
     * [MessageDao.observeUnreadCounts] for the underlying SQL.
     */
    fun observeAllConversations(): Flow<List<ConversationSummary>>

    /**
     * Marks every inbound message from [macAddress] as read. Idempotent.
     * Called when the user opens the corresponding chat.
     */
    suspend fun markPeerAsRead(macAddress: String)

    /**
     * Reactive view of the per-peer end-to-end encryption posture,
     * consumed by the chat screen to render the lock indicator next
     * to the peer's name. See [E2EEState] for the meaning of each
     * value.
     *
     * The flow always emits the latest state for the peer
     * immediately on subscription so a freshly recreated chat screen
     * does not stay in a perpetual "loading" placeholder.
     */
    fun observeSessionState(macAddress: String): Flow<E2EEState>

    /**
     * Hook called by `BlueWaveApplication` whenever the underlying
     * [com.example.bluewave_mobile.network.MessageTransport] reports a
     * fresh RFCOMM session for [macAddress].
     *
     * The repository pushes its libsignal `KEY_BUNDLE` frame at this
     * point so the peer can build a Signal session against us — this
     * is what makes the X3DH handshake symmetric in the absence of a
     * central key directory.
     */
    suspend fun onPeerLinkUp(macAddress: String)

    /**
     * Retrieves the latest message per device for building a conversation list.
     *
     * @return A [Flow] emitting a list of the most recent message from each conversation.
     */
    fun getLatestMessagePerDevice(): Flow<List<MessageEntity>>

    /**
     * Persists a new message to the local database.
     *
     * @param message The [MessageEntity] to insert.
     * @return The row ID of the newly inserted message.
     */
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * Processes an incoming encrypted byte array from a Bluetooth socket.
     *
     * This method is called by the network layer when raw bytes arrive.
     * It should handle decryption (if applicable) and persist the message
     * to the Room database, ensuring automatic UI updates via Flow.
     *
     * @param macAddress MAC address of the sending device.
     * @param senderName Display name of the sender.
     * @param rawData The raw byte array received from the Bluetooth socket.
     */
    suspend fun processIncomingMessage(macAddress: String, senderName: String, rawData: ByteArray)

    /**
     * Prepares and sends a text message to a remote device.
     *
     * Encrypts the plaintext message, persists it locally, and delegates
     * the actual byte transmission to the network layer.
     *
     * @param macAddress MAC address of the target device.
     * @param plaintext The plaintext message to send.
     */
    suspend fun sendMessage(macAddress: String, plaintext: String)

    /**
     * Deletes all messages for a specific device.
     *
     * @param macAddress MAC address of the device.
     */
    suspend fun deleteMessagesByDevice(macAddress: String)

    /**
     * Suspends all network activity towards a peer whose bond/encryption
     * keys went missing on Android 16
     * (`BluetoothDevice.ACTION_KEY_MISSING`).
     *
     * Implementations are expected to:
     *  * close any [java.io.InputStream] / [java.io.OutputStream] /
     *    [android.bluetooth.BluetoothSocket] currently held for that peer;
     *  * stop dispatching new outbound messages to that peer until
     *    [resumeNetworkOperations] is invoked.
     *
     * The method must NOT delete the bond programmatically — Android 16
     * keeps the metadata around so that a re-bond will succeed
     * automatically as soon as the user (or system) brings the peer back
     * online.
     *
     * @param macAddress MAC address of the affected peer.
     */
    suspend fun pauseNetworkOperations(macAddress: String)

    /**
     * Resumes network operations for a peer after a successful re-bond
     * (`BluetoothDevice.ACTION_ENCRYPTION_CHANGE`). Re-establishes the
     * RFCOMM client socket and flushes any messages that were enqueued
     * locally while the peer was offline.
     *
     * @param macAddress MAC address of the peer that just regained
     *                   a valid encryption key.
     */
    suspend fun resumeNetworkOperations(macAddress: String)
}

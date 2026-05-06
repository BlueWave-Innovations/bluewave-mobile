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
 *    radio transmission. The UI subscribes to [getMessagesByDevice] /
 *    [getLatestMessagePerDevice] and is updated automatically through
 *    Room's invalidation tracker once the row lands. The actual byte
 *    transmission over the BluetoothSocket is delegated to the network
 *    layer (`ConnectedThread`) — this interface intentionally does not
 *    expose any network primitives so the ViewModels never take a hard
 *    dependency on `android.bluetooth`.
 *
 *  * **Inbound** — the network layer hands raw bytes to
 *    [processIncomingMessage]. The implementation parses the on-wire
 *    frame `[12-byte IV || ciphertext+GCM-tag]`, decrypts it through
 *    [com.example.bluewave_mobile.crypto.CryptoManager.decrypt] and
 *    inserts the resulting [MessageEntity] into Room. Tampered frames
 *    are persisted with their IV so the UI can render them with the
 *    Material `errorContainer` treatment.
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

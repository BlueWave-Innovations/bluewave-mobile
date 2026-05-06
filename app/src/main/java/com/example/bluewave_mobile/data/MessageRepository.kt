package com.example.bluewave_mobile.data

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface that acts as the Single Source of Truth for message data.
 *
 * Abstracts data sources (Room database, Bluetooth network) from the ViewModel layer.
 * The ViewModel should depend on this interface, not on concrete implementations,
 * enabling testability through mock implementations.
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
}

package com.example.bluewave_mobile.data

import kotlinx.coroutines.flow.Flow

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
 */
class MessageRepositoryImpl(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun getMessagesByDevice(macAddress: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesByDevice(macAddress)
    }

    override fun getLatestMessagePerDevice(): Flow<List<MessageEntity>> {
        return messageDao.getLatestMessagePerDevice()
    }

    override suspend fun insertMessage(message: MessageEntity): Long {
        return messageDao.insertMessage(message)
    }

    override suspend fun processIncomingMessage(
        macAddress: String,
        senderName: String,
        rawData: ByteArray
    ) {
        // TODO: Integrate with CryptoManager for decryption (Step 25)
        // For now, store raw data as-is with empty IV (unencrypted)
        val message = MessageEntity(
            macAddress = macAddress,
            encryptedPayload = rawData,
            iv = ByteArray(0),
            isOutgoing = false,
            senderName = senderName
        )
        messageDao.insertMessage(message)
    }

    override suspend fun sendMessage(macAddress: String, plaintext: String) {
        // TODO: Integrate with CryptoManager for encryption (Step 23)
        // TODO: Integrate with BluetoothManager for sending (Step 19)
        // For now, store locally with empty IV (unencrypted)
        val message = MessageEntity(
            macAddress = macAddress,
            encryptedPayload = plaintext.toByteArray(Charsets.UTF_8),
            iv = ByteArray(0),
            isOutgoing = true,
            senderName = "Me"
        )
        messageDao.insertMessage(message)
    }

    override suspend fun deleteMessagesByDevice(macAddress: String) {
        messageDao.deleteMessagesByDevice(macAddress)
    }
}

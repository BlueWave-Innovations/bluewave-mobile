package com.example.bluewave_mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a text message exchanged via Bluetooth.
 *
 * Each message stores the encrypted payload along with its initialization vector (IV)
 * required for AES-256-GCM decryption. The [macAddress] field links the message
 * to a specific paired Bluetooth device.
 *
 * **Indexing.** [MessageDao.getMessagesByDevice] and
 * [MessageDao.getLatestMessagePerDevice] both filter / group on
 * [macAddress]. Without an explicit index Room/SQLite would fall back to
 * a full table scan for every chat-screen open, which is O(N) in the
 * total number of stored messages. The `@Index(value = ["macAddress"])`
 * declaration below creates a B-tree index covering that column,
 * dropping these lookups to O(log N) and producing a noticeable
 * improvement on devices with several thousand persisted rows.
 *
 * @property id Auto-generated unique identifier (primary key).
 * @property macAddress MAC address of the remote Bluetooth device.
 * @property encryptedPayload AES-256-GCM encrypted message content as a byte array.
 * @property iv 12-byte initialization vector used during encryption.
 *             A unique IV is generated for every encryption operation to ensure
 *             cryptographic security. An empty array indicates an unencrypted message.
 * @property timestamp Unix epoch milliseconds when the message was created.
 * @property isOutgoing True if the message was sent by this device, false if received.
 * @property senderName Display name of the sender device.
 * @property isRead `true` when the message has been displayed in the chat
 *                  screen by the local user. Outgoing messages default to
 *                  `true`; incoming messages start as `false` and flip to
 *                  `true` once the user opens the matching conversation.
 *                  Drives the `unreadCount` badge on the device list.
 * @property deliveryStatus Delivery lifecycle for outgoing messages:
 *                          0 = SENT (written to local DB, pushed to socket),
 *                          1 = DELIVERED (peer acknowledged receipt).
 *                          Incoming messages always stay at 0.
 * @property messageUuid Application-level unique id (UUID) used to
 *                       correlate outgoing messages with their ACKs.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["macAddress"])]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val macAddress: String,
    val encryptedPayload: ByteArray,
    val iv: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val senderName: String = "",
    val isRead: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val deliveryStatus: Int = 0,
    @ColumnInfo(defaultValue = "")
    val messageUuid: String = "",
    @ColumnInfo(defaultValue = "")
    val attachmentPath: String = "",
    @ColumnInfo(defaultValue = "")
    val attachmentName: String = "",
    @ColumnInfo(defaultValue = "")
    val attachmentMimeType: String = "",
    @ColumnInfo(defaultValue = "0")
    val attachmentSize: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val transferStatus: Int = 0,
) {
    companion object {
        const val STATUS_SENT = 0
        const val STATUS_DELIVERED = 1

        // Media transfer lifecycle
        const val TRANSFER_PENDING = 0
        const val TRANSFER_UPLOADING = 1
        const val TRANSFER_COMPLETED = 2
        const val TRANSFER_FAILED = 3
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageEntity

        if (id != other.id) return false
        if (macAddress != other.macAddress) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (timestamp != other.timestamp) return false
        if (isOutgoing != other.isOutgoing) return false
        if (senderName != other.senderName) return false
        if (isRead != other.isRead) return false
        if (deliveryStatus != other.deliveryStatus) return false
        if (messageUuid != other.messageUuid) return false
        if (attachmentPath != other.attachmentPath) return false
        if (attachmentName != other.attachmentName) return false
        if (attachmentMimeType != other.attachmentMimeType) return false
        if (attachmentSize != other.attachmentSize) return false
        if (transferStatus != other.transferStatus) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + macAddress.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + isRead.hashCode()
        result = 31 * result + deliveryStatus.hashCode()
        result = 31 * result + messageUuid.hashCode()
        result = 31 * result + attachmentPath.hashCode()
        result = 31 * result + attachmentName.hashCode()
        result = 31 * result + attachmentMimeType.hashCode()
        result = 31 * result + attachmentSize.hashCode()
        result = 31 * result + transferStatus.hashCode()
        return result
    }
}

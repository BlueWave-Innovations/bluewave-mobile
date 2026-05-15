package com.example.bluewave_mobile.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single message inside a multi-peer
 * [ChatGroupEntity].
 *
 * The schema mirrors [MessageEntity] but is keyed off [groupId]
 * instead of `macAddress`. Outgoing messages always sit under
 * `senderMac == ownerMac` for the local device; inbound messages
 * carry the originating peer's MAC so the UI can render the
 * "Алекс: привет" prefix without joining an extra table.
 *
 * Encryption-at-rest is the same AES-256-GCM scheme used by
 * [MessageEntity] — the [encryptedPayload] field holds the
 * ciphertext and [iv] the 12-byte nonce.
 *
 * Wire-level encryption uses the existing pairwise libsignal
 * sessions: outgoing group messages are fanned out to every member
 * (one [com.example.bluewave_mobile.network.BlueWaveFrame.Type.GROUP_CIPHERTEXT]
 * frame per peer), each encrypted with that peer's Double Ratchet
 * session. Inbound group messages decrypt via the same pairwise
 * session and route to this table by [groupId].
 *
 * @property id Auto-generated unique identifier (primary key).
 * @property groupId Reference to [ChatGroupEntity.id].
 * @property senderMac Uppercased MAC of the device that authored
 *                     the message. Equals the local MAC for
 *                     outgoing messages; otherwise the originating
 *                     peer.
 * @property encryptedPayload AES-256-GCM ciphertext of the message
 *                            body. Empty when the row is a tamper
 *                            sentinel produced by an inbound
 *                            decrypt failure.
 * @property iv 12-byte initialization vector used during AES
 *              encryption-at-rest.
 * @property timestamp Unix epoch milliseconds when the message was
 *                     created on the sender's device.
 * @property isOutgoing `true` when the local device authored the
 *                      message.
 * @property senderName Display name captured at send time. Used by
 *                      the UI to label inbound rows even after the
 *                      peer's profile cache rolls over.
 * @property isRead `true` once the user has viewed the group chat
 *                  with this row visible. Drives the unread badge
 *                  on the group's chat-list entry.
 */
@Entity(
    tableName = "group_message",
    foreignKeys = [
        ForeignKey(
            entity = ChatGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId"])],
)
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val groupId: String,
    val senderMac: String,
    val encryptedPayload: ByteArray,
    val iv: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val senderName: String = "",
    val isRead: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupMessageEntity) return false

        if (id != other.id) return false
        if (groupId != other.groupId) return false
        if (senderMac != other.senderMac) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (timestamp != other.timestamp) return false
        if (isOutgoing != other.isOutgoing) return false
        if (senderName != other.senderName) return false
        if (isRead != other.isRead) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + senderMac.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + senderName.hashCode()
        result = 31 * result + isRead.hashCode()
        return result
    }
}

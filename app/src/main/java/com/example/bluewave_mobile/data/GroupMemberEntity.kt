package com.example.bluewave_mobile.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many bridge between a [ChatGroupEntity] and its
 * participating peer MAC addresses.
 *
 * Each row says "peer [peerMac] is part of group [groupId]". The
 * local device's own MAC also appears in this table so the UI can
 * render "you + 3 others" without a special-case branch.
 *
 * Composite primary key (groupId, peerMac) prevents accidentally
 * adding the same peer twice. The CASCADE foreign key on [groupId]
 * mirrors `peer_folder_assignment`: deleting a group also drops
 * every membership row.
 *
 * @property groupId  Reference to [ChatGroupEntity.id].
 * @property peerMac  Uppercased MAC address of the participant.
 * @property joinedAt Unix epoch milliseconds the member was added
 *                    locally. Used to sort the member list by join
 *                    order and to surface a "joined just now"
 *                    timestamp in the group info screen.
 */
@Entity(
    tableName = "group_member",
    primaryKeys = ["groupId", "peerMac"],
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
data class GroupMemberEntity(
    val groupId: String,
    val peerMac: String,
    val joinedAt: Long = System.currentTimeMillis(),
)

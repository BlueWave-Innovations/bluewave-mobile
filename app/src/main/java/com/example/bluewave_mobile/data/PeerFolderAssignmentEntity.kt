package com.example.bluewave_mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many bridge between peers (keyed by their RFCOMM-stable
 * MAC address) and the [ChatFolderEntity] folders the local user has
 * assigned them to.
 *
 * Composite primary key — at most one row per (peer, folder) pair.
 * The foreign-key cascade on `folderId` keeps the table in sync
 * when a folder is deleted: every assignment for that folder is
 * automatically dropped, so the chip filter stays consistent.
 */
@Entity(
    tableName = "peer_folder_assignment",
    primaryKeys = ["peerId", "folderId"],
    foreignKeys = [
        ForeignKey(
            entity = ChatFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["folderId"]),
    ],
)
data class PeerFolderAssignmentEntity(
    /** Uppercased MAC address of the peer. */
    @ColumnInfo(name = "peerId")
    val peerId: String,

    /** Folder primary key from [ChatFolderEntity.id]. */
    @ColumnInfo(name = "folderId")
    val folderId: String,
)

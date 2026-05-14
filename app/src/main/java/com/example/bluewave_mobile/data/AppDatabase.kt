package com.example.bluewave_mobile.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Main Room database for the BlueWave messenger.
 *
 * Stores:
 *  * encrypted message history ([MessageEntity]);
 *  * cached profile cards pushed by peers ([PeerProfileEntity]);
 *  * the user-defined chat-folder taxonomy ([ChatFolderEntity] +
 *    [PeerFolderAssignmentEntity]);
 *  * the multi-peer group taxonomy ([ChatGroupEntity] +
 *    [GroupMemberEntity] + [GroupMessageEntity]).
 *
 * Room generates the implementation of this abstract class at compile time via KSP.
 *
 * @see MessageEntity
 * @see MessageDao
 * @see PeerProfileEntity
 * @see PeerProfileDao
 * @see ChatFolderEntity
 * @see ChatFolderDao
 * @see ChatGroupEntity
 * @see ChatGroupDao
 */
@Database(
    entities = [
        MessageEntity::class,
        PeerProfileEntity::class,
        ChatFolderEntity::class,
        PeerFolderAssignmentEntity::class,
        ChatGroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Provides access to the message data access object.
     */
    abstract fun messageDao(): MessageDao

    /**
     * Provides access to the peer-profile data access object.
     * The repository upserts this table from inbound
     * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
     * frames, and the chat / device-list UIs subscribe to it
     * through [PeerProfileDao.observeProfile] /
     * [PeerProfileDao.observeAll].
     */
    abstract fun peerProfileDao(): PeerProfileDao

    /**
     * DAO for the chat-folder taxonomy and the peer→folder bridge
     * table. Used by `FolderRepository` and the device-list filter
     * chips.
     */
    abstract fun chatFolderDao(): ChatFolderDao

    /**
     * DAO for the multi-peer group taxonomy. Backs `GroupRepository`
     * and the group-chat / group-list UIs introduced in Phase 6.
     */
    abstract fun chatGroupDao(): ChatGroupDao
}

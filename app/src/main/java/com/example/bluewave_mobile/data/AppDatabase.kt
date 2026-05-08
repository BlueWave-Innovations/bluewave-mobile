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
 *    [PeerFolderAssignmentEntity]).
 *
 * Room generates the implementation of this abstract class at compile time via KSP.
 *
 * @see MessageEntity
 * @see MessageDao
 * @see PeerProfileEntity
 * @see PeerProfileDao
 * @see ChatFolderEntity
 * @see ChatFolderDao
 */
@Database(
    entities = [
        MessageEntity::class,
        PeerProfileEntity::class,
        ChatFolderEntity::class,
        PeerFolderAssignmentEntity::class,
    ],
    version = 5,
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
}

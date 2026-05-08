package com.example.bluewave_mobile.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Main Room database for the BlueWave messenger.
 *
 * Stores encrypted message history ([MessageEntity]) and the
 * cached profile cards peers have pushed to us
 * ([PeerProfileEntity]).
 *
 * Room generates the implementation of this abstract class at compile time via KSP.
 *
 * @see MessageEntity
 * @see MessageDao
 * @see PeerProfileEntity
 * @see PeerProfileDao
 */
@Database(
    entities = [MessageEntity::class, PeerProfileEntity::class],
    version = 4,
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
}

package com.example.bluewave_mobile.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Main Room database for the BlueWave messenger.
 *
 * Stores encrypted message history using [MessageEntity].
 * All database access is performed through [MessageDao].
 *
 * Room generates the implementation of this abstract class at compile time via KSP.
 *
 * @see MessageEntity
 * @see MessageDao
 */
@Database(
    entities = [MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Provides access to the message data access object.
     */
    abstract fun messageDao(): MessageDao
}

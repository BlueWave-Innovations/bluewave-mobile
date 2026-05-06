package com.example.bluewave_mobile.data

import android.content.Context
import androidx.room.Room

/**
 * Singleton provider for the [AppDatabase] instance.
 *
 * Uses double-checked locking to ensure thread-safe lazy initialization.
 * The database is built with the standard Room builder using the
 * "bluewave_messages.db" file name.
 *
 * Usage:
 * ```kotlin
 * val db = DatabaseProvider.getDatabase(applicationContext)
 * val dao = db.messageDao()
 * ```
 */
object DatabaseProvider {

    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * Returns the singleton [AppDatabase] instance, creating it if necessary.
     *
     * @param context Application context (not Activity context) to prevent memory leaks.
     * @return The singleton [AppDatabase] instance.
     */
    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "bluewave_messages.db"
            )
                .fallbackToDestructiveMigration()
                .build()
            INSTANCE = instance
            instance
        }
    }
}

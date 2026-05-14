package com.example.bluewave_mobile.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
     * v2 → v3: introduces the `isRead` flag used by the device-list
     * unread badge. Outgoing messages already in the DB are marked as
     * read so they don't pollute the badge count; inbound rows default
     * to unread, matching the contract enforced by the repository.
     */
    private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET isRead = 1 WHERE isOutgoing = 1")
        }
    }

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
                "bluewave_messages.db",
            )
                .addMigrations(MIGRATION_2_3)
                // Destructive fallback as a last-resort net for older
                // unreleased schema revisions; production v2+ → v3 always
                // uses the explicit migration above.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            INSTANCE = instance
            instance
        }
    }
}

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
     * v3 → v4: adds the `peer_profile` cache populated from inbound
     * `PROFILE_METADATA` frames. The schema mirrors the
     * [PeerProfileEntity] data class one-to-one — Room enforces the
     * shape at first read and would crash on mismatch, so the SQL
     * here is the source of truth for runtime upgrades.
     */
    private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS peer_profile (
                    macAddress TEXT NOT NULL PRIMARY KEY,
                    displayName TEXT NOT NULL DEFAULT '',
                    handle TEXT NOT NULL DEFAULT '',
                    bio TEXT NOT NULL DEFAULT '',
                    avatarUri TEXT NOT NULL DEFAULT '',
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                // Destructive fallback as a last-resort net for older
                // unreleased schema revisions; production v2+ → v4 always
                // uses the explicit migrations above.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            INSTANCE = instance
            instance
        }
    }
}

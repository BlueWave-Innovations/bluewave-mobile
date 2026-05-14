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
     * v4 → v5: introduces the chat-folder taxonomy.
     *  * `chat_folder` holds one row per built-in / user-created
     *    folder; `builtInKey` is non-null for seeded rows so the
     *    UI can resolve a localised label.
     *  * `peer_folder_assignment` is a many-to-many bridge between
     *    peers (by MAC) and folders, with a CASCADE foreign key so
     *    deleting a folder also drops every assignment.
     *
     * Built-in folders are *not* seeded by this migration — that
     * happens in `FolderRepository.seedBuiltInsIfNeeded` so the UI
     * thread never blocks on a Room migration. The migration only
     * has to leave the schema in a state Room recognises.
     */
    private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_folder (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    builtInKey TEXT,
                    sortOrder INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS peer_folder_assignment (
                    peerId TEXT NOT NULL,
                    folderId TEXT NOT NULL,
                    PRIMARY KEY (peerId, folderId),
                    FOREIGN KEY (folderId) REFERENCES chat_folder(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_peer_folder_assignment_folderId
                ON peer_folder_assignment(folderId)
                """.trimIndent(),
            )
        }
    }

    /**
     * v5 → v6: introduces the multi-peer group taxonomy.
     *  * `chat_group` holds one row per group (id, name, owner MAC,
     *    creation timestamp).
     *  * `group_member` is the many-to-many bridge between a group
     *    and its participating peer MACs. CASCADE foreign key on
     *    `groupId` so removing a group also drops every membership.
     *  * `group_message` mirrors the regular `messages` table but is
     *    keyed off `groupId`. The encryption-at-rest scheme is the
     *    same AES-256-GCM that backs single-peer messages; the wire
     *    layer fan-outs every send through the existing pairwise
     *    libsignal sessions and lands inbound rows in this table by
     *    `groupId`.
     */
    private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS chat_group (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    ownerMac TEXT NOT NULL DEFAULT '',
                    createdAt INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_member (
                    groupId TEXT NOT NULL,
                    peerMac TEXT NOT NULL,
                    joinedAt INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (groupId, peerMac),
                    FOREIGN KEY (groupId) REFERENCES chat_group(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_group_member_groupId
                ON group_member(groupId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS group_message (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    groupId TEXT NOT NULL,
                    senderMac TEXT NOT NULL,
                    encryptedPayload BLOB NOT NULL,
                    iv BLOB NOT NULL,
                    timestamp INTEGER NOT NULL DEFAULT 0,
                    isOutgoing INTEGER NOT NULL DEFAULT 0,
                    senderName TEXT NOT NULL DEFAULT '',
                    isRead INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (groupId) REFERENCES chat_group(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_group_message_groupId
                ON group_message(groupId)
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // Destructive fallback as a last-resort net for older
                // unreleased schema revisions; production v2+ → v6 always
                // uses the explicit migrations above.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            INSTANCE = instance
            instance
        }
    }
}

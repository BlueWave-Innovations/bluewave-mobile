package com.example.bluewave_mobile.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single Source of Truth for the chat-folder taxonomy.
 *
 * Owns:
 *  * the list of folders ([observeFolders]) — Compose subscribes
 *    once and re-renders the chip row whenever a folder is created
 *    / renamed / deleted;
 *  * the peer→folder bridge ([observeAssignments]) — used to filter
 *    the device-list chats by the active chip;
 *  * lifecycle hooks for built-in seeding ([seedBuiltInsIfNeeded]).
 *
 * The repository is intentionally thin — it only delegates to the
 * DAO and never touches Bluetooth or crypto.
 */
class FolderRepository(
    private val folderDao: ChatFolderDao,
) {

    /** Hot stream of every folder in display order. */
    fun observeFolders(): Flow<List<ChatFolderEntity>> = folderDao.observeAll()

    /** Hot stream of every (peer, folder) assignment. */
    fun observeAssignments(): Flow<List<PeerFolderAssignmentEntity>> =
        folderDao.observeAllAssignments()

    /** Folders the given peer is currently assigned to. */
    fun observePeerFolders(peerId: String): Flow<List<String>> =
        folderDao.observePeerFolders(peerId.uppercase())

    /**
     * Insert the [BuiltInFolder] rows the very first time the app
     * is launched after install or after a fresh-install
     * destructive migration. The DAO uses
     * `OnConflictStrategy.REPLACE`, so calling this method on an
     * already-seeded database overwrites the rows but does not
     * touch their assignments.
     */
    suspend fun seedBuiltInsIfNeeded() {
        val existing = folderDao.countBuiltIns(BuiltInFolder.ALL)
        if (existing >= BuiltInFolder.ALL.size) return
        val now = System.currentTimeMillis()
        val seeded = BuiltInFolder.ALL.mapIndexed { index, key ->
            ChatFolderEntity(
                id = "builtin:$key",
                name = "",
                builtInKey = key,
                sortOrder = index.toLong(),
                createdAt = now,
            )
        }
        folderDao.upsertAll(seeded)
    }

    /**
     * Create a fresh user folder and return its newly-issued id.
     * The id is a v4 UUID — short enough for sqlite indexing and
     * impossible to clash with the `builtin:*` namespace.
     */
    suspend fun createFolder(name: String): String {
        val id = UUID.randomUUID().toString()
        folderDao.upsert(
            ChatFolderEntity(
                id = id,
                name = name.trim(),
                builtInKey = null,
                // User folders sort after every built-in (which sit
                // at sortOrder 0 / 1). We use createdAt as the
                // tiebreaker so the chip row is stable.
                sortOrder = USER_FOLDER_SORT_BASE,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    /** Rename an existing folder; trims the value at the call site. */
    suspend fun renameFolder(folderId: String, newName: String) {
        folderDao.rename(folderId, newName.trim())
    }

    /**
     * Delete a folder and (via the foreign-key cascade) every
     * peer-assignment referencing it.
     */
    suspend fun deleteFolder(folderId: String) {
        folderDao.delete(folderId)
    }

    /** Add a peer to a folder; idempotent thanks to REPLACE. */
    suspend fun assign(peerId: String, folderId: String) {
        folderDao.assign(
            PeerFolderAssignmentEntity(
                peerId = peerId.uppercase(),
                folderId = folderId,
            ),
        )
    }

    /** Remove a peer from a folder. No-op if not currently assigned. */
    suspend fun unassign(peerId: String, folderId: String) {
        folderDao.unassign(peerId.uppercase(), folderId)
    }

    private companion object {
        /**
         * Sort-order base for user-created folders; sits well above
         * the built-in 0/1 values so a freshly-seeded install always
         * draws Work / Family first.
         */
        const val USER_FOLDER_SORT_BASE: Long = 1_000L
    }
}

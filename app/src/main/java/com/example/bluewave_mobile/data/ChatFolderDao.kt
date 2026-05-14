package com.example.bluewave_mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Persistence facade for [ChatFolderEntity] and the
 * [PeerFolderAssignmentEntity] bridge table.
 *
 * The DAO lives on the same Room database as messages and peer
 * profiles so callers can share a single transaction (used during
 * folder seeding on first launch).
 */
@Dao
interface ChatFolderDao {

    /** All folders ordered for chip-row display. */
    @Query("SELECT * FROM chat_folder ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<ChatFolderEntity>>

    /** Snapshot list of every folder; used by repositories at boot. */
    @Query("SELECT * FROM chat_folder ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun listAll(): List<ChatFolderEntity>

    /** Lookup by primary key, used for rename / delete dialogs. */
    @Query("SELECT * FROM chat_folder WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ChatFolderEntity?

    /** True when the seeded built-ins have not been planted yet. */
    @Query("SELECT COUNT(*) FROM chat_folder WHERE builtInKey IN (:keys)")
    suspend fun countBuiltIns(keys: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: ChatFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<ChatFolderEntity>)

    @Update
    suspend fun update(folder: ChatFolderEntity)

    @Query("UPDATE chat_folder SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM chat_folder WHERE id = :id")
    suspend fun delete(id: String)

    /** Every (peerId → [folderId]) assignment for the chip filter. */
    @Query("SELECT folderId FROM peer_folder_assignment WHERE peerId = :peerId")
    fun observePeerFolders(peerId: String): Flow<List<String>>

    /** Snapshot of all assignments — used to compute folder counts. */
    @Query("SELECT * FROM peer_folder_assignment")
    fun observeAllAssignments(): Flow<List<PeerFolderAssignmentEntity>>

    /** All peers sitting in the named folder. */
    @Query("SELECT peerId FROM peer_folder_assignment WHERE folderId = :folderId")
    fun observeFolderMembers(folderId: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun assign(assignment: PeerFolderAssignmentEntity)

    @Query(
        "DELETE FROM peer_folder_assignment WHERE peerId = :peerId AND folderId = :folderId",
    )
    suspend fun unassign(peerId: String, folderId: String)

    @Query("DELETE FROM peer_folder_assignment WHERE peerId = :peerId")
    suspend fun unassignAllForPeer(peerId: String)
}

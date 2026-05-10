package com.example.bluewave_mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the multi-peer group taxonomy.
 *
 * Splits across three logical concerns:
 *
 *  * groups       — CRUD on [ChatGroupEntity];
 *  * memberships  — CRUD on [GroupMemberEntity] (the peer ↔ group
 *                   bridge);
 *  * messages     — CRUD on [GroupMessageEntity].
 *
 * Each section is a flat Kotlin block so the generated DAO is one
 * file and there is no impedance mismatch between abstract method
 * names and the SQL they map to.
 */
@Dao
interface ChatGroupDao {

    // ---------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: ChatGroupEntity)

    @Query("SELECT * FROM chat_group WHERE id = :groupId LIMIT 1")
    suspend fun findGroup(groupId: String): ChatGroupEntity?

    @Query("DELETE FROM chat_group WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)

    /**
     * Stream of every locally-known group, sorted by recency
     * (newest [ChatGroupEntity.createdAt] first). Drives the chat
     * list's group section.
     */
    @Query("SELECT * FROM chat_group ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<ChatGroupEntity>>

    // ---------------------------------------------------------------
    // Memberships
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_member WHERE groupId = :groupId AND peerMac = :peerMac")
    suspend fun deleteMember(groupId: String, peerMac: String)

    @Query("SELECT * FROM group_member WHERE groupId = :groupId ORDER BY joinedAt ASC")
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_member ORDER BY joinedAt ASC")
    fun observeAllMemberships(): Flow<List<GroupMemberEntity>>

    @Query("SELECT peerMac FROM group_member WHERE groupId = :groupId")
    suspend fun memberMacs(groupId: String): List<String>

    // ---------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: GroupMessageEntity): Long

    @Query("SELECT * FROM group_message WHERE groupId = :groupId ORDER BY timestamp ASC")
    fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>>

    @Query(
        """
        SELECT * FROM group_message
        WHERE groupId = :groupId
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun latestMessage(groupId: String): GroupMessageEntity?

    @Query(
        """
        SELECT COUNT(*) FROM group_message
        WHERE groupId = :groupId AND isOutgoing = 0 AND isRead = 0
        """,
    )
    suspend fun unreadCount(groupId: String): Int

    @Query(
        """
        UPDATE group_message
        SET isRead = 1
        WHERE groupId = :groupId AND isOutgoing = 0 AND isRead = 0
        """,
    )
    suspend fun markAllRead(groupId: String)

    /**
     * One-shot snapshot of every group with a stitched member /
     * latest-message preview. Used to seed the chat list when the
     * user first opens the app — afterwards the list re-renders off
     * the [observeGroups] stream.
     */
    @Transaction
    suspend fun groupSummaries(): List<GroupSummary> {
        val groups = allGroups()
        return groups.map { group ->
            GroupSummary(
                group = group,
                memberMacs = memberMacs(group.id),
                latestMessage = latestMessage(group.id),
                unreadCount = unreadCount(group.id),
            )
        }
    }

    @Query("SELECT * FROM chat_group ORDER BY createdAt DESC")
    suspend fun allGroups(): List<ChatGroupEntity>
}

/**
 * Aggregate row produced by [ChatGroupDao.groupSummaries] — one
 * group plus the bits the chat list and the group-info screen
 * usually want in the same render pass.
 */
data class GroupSummary(
    val group: ChatGroupEntity,
    val memberMacs: List<String>,
    val latestMessage: GroupMessageEntity?,
    val unreadCount: Int,
)

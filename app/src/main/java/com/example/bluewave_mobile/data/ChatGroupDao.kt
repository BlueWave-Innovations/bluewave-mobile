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
abstract class ChatGroupDao {

    // ---------------------------------------------------------------
    // Groups
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertGroup(group: ChatGroupEntity)

    @Query("SELECT * FROM chat_group WHERE id = :groupId LIMIT 1")
    abstract suspend fun findGroup(groupId: String): ChatGroupEntity?

    @Query("DELETE FROM chat_group WHERE id = :groupId")
    abstract suspend fun deleteGroup(groupId: String)

    /**
     * Stream of every locally-known group, sorted by recency
     * (newest [ChatGroupEntity.createdAt] first). Drives the chat
     * list's group section.
     */
    @Query("SELECT * FROM chat_group ORDER BY createdAt DESC")
    abstract fun observeGroups(): Flow<List<ChatGroupEntity>>

    // ---------------------------------------------------------------
    // Memberships
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMember(member: GroupMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMembers(members: List<GroupMemberEntity>)

    @Query("DELETE FROM group_member WHERE groupId = :groupId AND peerMac = :peerMac")
    abstract suspend fun deleteMember(groupId: String, peerMac: String)

    @Query("SELECT * FROM group_member WHERE groupId = :groupId ORDER BY joinedAt ASC")
    abstract fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_member ORDER BY joinedAt ASC")
    abstract fun observeAllMemberships(): Flow<List<GroupMemberEntity>>

    @Query("SELECT peerMac FROM group_member WHERE groupId = :groupId")
    abstract suspend fun memberMacs(groupId: String): List<String>

    // ---------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessage(message: GroupMessageEntity): Long

    @Query("SELECT * FROM group_message WHERE groupId = :groupId ORDER BY timestamp ASC")
    abstract fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_message ORDER BY timestamp DESC")
    abstract fun observeAllGroupMessages(): Flow<List<GroupMessageEntity>>

    @Query(
        """
        SELECT * FROM group_message
        WHERE groupId = :groupId
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    abstract suspend fun latestMessage(groupId: String): GroupMessageEntity?

    @Query(
        """
        SELECT COUNT(*) FROM group_message
        WHERE groupId = :groupId AND isOutgoing = 0 AND isRead = 0
        """,
    )
    abstract suspend fun unreadCount(groupId: String): Int

    @Query(
        """
        UPDATE group_message
        SET isRead = 1
        WHERE groupId = :groupId AND isOutgoing = 0 AND isRead = 0
        """,
    )
    abstract suspend fun markAllRead(groupId: String)

    /**
     * One-shot snapshot of every group with a stitched member /
     * latest-message preview. Used to seed the chat list when the
     * user first opens the app — afterwards the list re-renders off
     * the [observeGroups] stream.
     *
     * Marked [Transaction] so the four constituent queries run
     * atomically and cannot see interleaved mutations from other
     * coroutines.
     */
    @Transaction
    open suspend fun groupSummaries(): List<GroupSummary> {
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
    abstract suspend fun allGroups(): List<ChatGroupEntity>

    /**
     * Atomically inserts a group and its members. Used by
     * [GroupRepositoryImpl.createGroup] so the two writes are
     * never observed partially (group without members).
     */
    @Transaction
    open suspend fun createGroupWithMembers(
        group: ChatGroupEntity,
        members: List<GroupMemberEntity>,
    ) {
        upsertGroup(group)
        upsertMembers(members)
    }
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

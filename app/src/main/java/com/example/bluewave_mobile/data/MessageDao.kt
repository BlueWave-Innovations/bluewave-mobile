package com.example.bluewave_mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for [MessageEntity].
 *
 * All read operations return reactive [Flow] streams, enabling automatic UI updates
 * when the underlying data changes (Room's built-in invalidation tracker).
 * All write operations are [suspend] functions, enforcing asynchronous execution
 * in accordance with Room's Coroutines-first paradigm.
 */
@Dao
interface MessageDao {

    /**
     * Inserts a new message into the database.
     * If a conflict occurs on the primary key, the existing record is replaced.
     *
     * @param message The [MessageEntity] to insert.
     * @return The row ID of the newly inserted message.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    /**
     * Retrieves the complete chat history for a specific device,
     * ordered by timestamp in ascending order (oldest first).
     *
     * @param macAddress The MAC address of the remote Bluetooth device.
     * @return A reactive [Flow] emitting the list of messages whenever data changes.
     */
    @Query("SELECT * FROM messages WHERE macAddress = :macAddress ORDER BY timestamp ASC")
    fun getMessagesByDevice(macAddress: String): Flow<List<MessageEntity>>

    /**
     * Retrieves all messages from the database, ordered by timestamp descending.
     * Used for displaying recent conversations overview.
     *
     * @return A reactive [Flow] emitting all messages.
     */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    /**
     * Retrieves the last message for each unique MAC address.
     * Used to build a conversation list showing the latest message per device.
     *
     * @return A reactive [Flow] emitting one message per device (the most recent).
     */
    @Query(
        """
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT macAddress, MAX(timestamp) AS maxTs
            FROM messages
            GROUP BY macAddress
        ) latest ON m.macAddress = latest.macAddress AND m.timestamp = latest.maxTs
        ORDER BY m.timestamp DESC
        """,
    )
    fun getLatestMessagePerDevice(): Flow<List<MessageEntity>>

    /**
     * Deletes all messages associated with a specific device.
     *
     * @param macAddress The MAC address of the device whose messages should be deleted.
     */
    @Query("DELETE FROM messages WHERE macAddress = :macAddress")
    suspend fun deleteMessagesByDevice(macAddress: String)

    /**
     * Returns the total count of messages in the database.
     * Useful for analytics and debugging.
     *
     * @return The number of messages stored.
     */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    /**
     * Streams `(macAddress, unreadCount)` rows where `unreadCount` is the
     * number of inbound, not-yet-read messages for that peer. Outgoing
     * rows are excluded by construction (they are inserted with
     * `isRead = true`).
     *
     * Used by the device-list screen to render the unread badge next to
     * each existing-chat row.
     */
    @Query(
        """
        SELECT macAddress AS macAddress, COUNT(*) AS unreadCount
        FROM messages
        WHERE isOutgoing = 0 AND isRead = 0
        GROUP BY macAddress
        """,
    )
    fun observeUnreadCounts(): Flow<List<UnreadByPeer>>

    /**
     * Marks every inbound message from [macAddress] as read. Idempotent
     * — subsequent invocations on a fully-read peer are no-ops on the
     * SQLite write path.
     */
    @Query("UPDATE messages SET isRead = 1 WHERE macAddress = :macAddress AND isOutgoing = 0 AND isRead = 0")
    suspend fun markPeerAsRead(macAddress: String)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE messageUuid = :uuid")
    suspend fun updateDeliveryStatus(uuid: String, status: Int)

    @Query("UPDATE messages SET transferStatus = :status WHERE messageUuid = :uuid")
    suspend fun updateTransferStatus(uuid: String, status: Int)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)
}

/**
 * Projection row for [MessageDao.observeUnreadCounts]. Kept as a
 * top-level data class (instead of a `Pair`) so Room can map columns
 * by name without reflection-on-generics tricks.
 */
data class UnreadByPeer(
    val macAddress: String,
    val unreadCount: Int,
)

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
    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages GROUP BY macAddress
        )
        ORDER BY timestamp DESC
    """)
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
}

package com.example.bluewave_mobile.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One chat-organising folder owned by the local user.
 *
 * Folders fall into two flavours:
 *  * **built-in** — seeded once on first launch (Work, Family) so a
 *    fresh install ships with reasonable defaults. The [builtInKey]
 *    column is non-null for these; the Compose layer maps the key
 *    to the localised label so the same row reads as "Работа" or
 *    "Work" depending on the current locale.
 *  * **user-created** — name is literal, [builtInKey] is `null`.
 *
 * Two pseudo-folders ("All" and "Nearby") are *not* stored in this
 * table — they are computed views surfaced as chips by the
 * device-list screen.
 */
@Entity(tableName = "chat_folder")
data class ChatFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Literal label for user-created folders; ignored for built-ins. */
    @ColumnInfo(name = "name", defaultValue = "")
    val name: String,

    /**
     * Stable key for built-in folders, null for user-created ones.
     * Compose maps this to a string resource (see
     * [com.example.bluewave_mobile.data.BuiltInFolder]).
     */
    @ColumnInfo(name = "builtInKey")
    val builtInKey: String?,

    /**
     * Ordinal used to draw chips left-to-right deterministically.
     * Built-ins seed at 0 / 1, user folders take incrementing
     * timestamps so newest user folders sort to the right.
     */
    @ColumnInfo(name = "sortOrder", defaultValue = "0")
    val sortOrder: Long,

    /** Wall-clock millis the folder was created. */
    @ColumnInfo(name = "createdAt", defaultValue = "0")
    val createdAt: Long,
)

/**
 * Stable identifiers for the seeded built-in folders. The UI maps
 * these to localised string resources (see `folders_builtin_*`).
 */
object BuiltInFolder {
    const val WORK = "WORK"
    const val FAMILY = "FAMILY"

    /** All known built-in keys, sorted in chip-display order. */
    val ALL: List<String> = listOf(WORK, FAMILY)
}

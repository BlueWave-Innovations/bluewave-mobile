package com.example.bluewave_mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single multi-peer chat group.
 *
 * Groups are identified by a stable opaque [id] (random UUID v4 in
 * production) so the same group can be referenced from every member
 * even when display names collide. The [ownerMac] field records the
 * MAC of the device that originally created the group; for now only
 * the owner is allowed to add or remove members, but the schema
 * leaves room for extending that policy later without a migration.
 *
 * The group taxonomy splits across three tables:
 *
 *  * [ChatGroupEntity] (this one)        — one row per group;
 *  * [GroupMemberEntity]                  — many-to-many bridge
 *                                            mapping a group to its
 *                                            participating MAC
 *                                            addresses (including the
 *                                            local device's own MAC);
 *  * [GroupMessageEntity]                 — encrypted message history
 *                                            for the group.
 *
 * @property id Stable opaque identifier — uppercased UUID v4 in
 *              production, but the schema does not enforce a format
 *              so tests can use deterministic ids like `"g-1"`.
 * @property name User-visible group name. Free-form, not unique.
 * @property ownerMac Uppercased MAC of the device that created the
 *                   group. Stored even on remote devices so the UI
 *                   can show "Owner: …" when needed.
 * @property createdAt Unix epoch milliseconds when the group was
 *                    created on the owner's device. Replicated
 *                    verbatim onto every member's local row so the
 *                    chat list can sort groups by recency without
 *                    relying on the owner's clock.
 */
@Entity(tableName = "chat_group")
data class ChatGroupEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val ownerMac: String,
    val createdAt: Long = System.currentTimeMillis(),
)

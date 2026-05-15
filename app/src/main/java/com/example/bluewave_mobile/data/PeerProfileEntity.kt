package com.example.bluewave_mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached profile card a peer has pushed to us through a
 * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
 * frame.
 *
 * The repository decrypts each inbound `PROFILE_METADATA` body
 * with the active libsignal session, parses
 * [com.example.bluewave_mobile.preferences.LocalProfileCodec] and
 * upserts the result into this table. The Profile screen of the
 * peer (and the chat top bar / contact-list rows) react to the
 * resulting Room flow.
 *
 * The MAC address is the natural primary key because BlueWave keys
 * every conversation off `BluetoothDevice.address`. There is at
 * most one profile per peer; pushing a fresh one overwrites the
 * stored row.
 *
 * @property macAddress Uppercase MAC address of the peer, used as
 *                       the primary key. Matches the same
 *                       canonical form
 *                       [com.example.bluewave_mobile.data.MessageRepositoryImpl]
 *                       uses for every other peer-keyed lookup.
 * @property displayName Free-form display name picked by the peer.
 *                       Empty when the peer has never set one.
 * @property handle      Canonicalised `@handle` (always either
 *                       empty or starts with `@`).
 * @property bio         Free-form short bio (≤ a few hundred
 *                       characters in practice; the schema does
 *                       not enforce a hard cap).
 * @property avatarUri   `content://` or `file://` URI on the
 *                       peer's device — useful as a hint when both
 *                       devices share an OS-level photo store but
 *                       NOT a self-contained image. The avatar
 *                       picker on the receiver side falls back to
 *                       the initials avatar when the URI cannot be
 *                       resolved.
 * @property updatedAt   Wall-clock timestamp (Unix epoch ms) when
 *                       the row was last upserted. Used by the
 *                       chats list to surface the most recently
 *                       refreshed name when several updates land
 *                       within the same Compose frame.
 */
@Entity(tableName = "peer_profile")
data class PeerProfileEntity(
    @PrimaryKey
    val macAddress: String,
    val displayName: String = "",
    val handle: String = "",
    val bio: String = "",
    val avatarUri: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

package com.example.bluewave_mobile.data

import kotlinx.coroutines.flow.Flow

/**
 * Single Source of Truth for the multi-peer group taxonomy.
 *
 * Mirrors [MessageRepository] but for groups: outbound writes land
 * in [ChatGroupDao] first (so the chat-list UI re-renders
 * immediately) before the network layer fans out the encrypted
 * frames to every member.
 *
 * Wire layer: every group payload is a regular pairwise libsignal
 * frame riding inside [com.example.bluewave_mobile.network.BlueWaveFrame.Type.GROUP_INVITE]
 * or [com.example.bluewave_mobile.network.BlueWaveFrame.Type.GROUP_MESSAGE].
 * That keeps the cryptographic primitive identical to the regular
 * 1:1 chat path — sender keys would be cheaper for very large
 * groups, but Bluetooth groups are bounded by RFCOMM's connection
 * count anyway and the duplicate ratchet steps are negligible at
 * those sizes.
 *
 * **Concurrency contract.** Every `suspend` method MUST be
 * cancellable. Implementations should serialise their internal
 * state with a `Mutex` so concurrent encrypt/decrypt calls cannot
 * corrupt the per-peer libsignal session caches.
 */
interface GroupRepository {

    /** Reactive view of every locally-known group, newest first. */
    fun observeGroups(): Flow<List<ChatGroupEntity>>

    /** Reactive view of every membership row, used by the chat list. */
    fun observeAllMemberships(): Flow<List<GroupMemberEntity>>

    /** Reactive view of one group's members. */
    fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>>

    /** Reactive view of one group's encrypted message history. */
    fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>>

    /**
     * Returns a one-shot snapshot of every group with a stitched
     * member / latest-message preview. Used to seed the chat list
     * when the user first opens the app.
     */
    suspend fun groupSummaries(): List<GroupSummary>

    /** Returns the [ChatGroupEntity] for [groupId], or `null`. */
    suspend fun findGroup(groupId: String): ChatGroupEntity?

    /**
     * Creates a new group with [name] and the supplied [memberMacs]
     * (uppercased internally). Persists the group locally and pushes
     * a [com.example.bluewave_mobile.network.BlueWaveFrame.Type.GROUP_INVITE]
     * frame to every member that the local device has a SECURE
     * libsignal session with. Members the local device has no
     * session with yet receive the invite once the session reaches
     * SECURE — see [onPeerLinkUp] / `onSessionSecure` integration in
     * the implementation.
     *
     * Returns the freshly-created group's stable id.
     */
    suspend fun createGroup(name: String, memberMacs: List<String>): String

    /**
     * Encrypts [plaintext] under every member's pairwise libsignal
     * session and ships one
     * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.GROUP_MESSAGE]
     * frame per recipient. Persists the outgoing copy in the local
     * `group_message` table immediately so the UI re-renders before
     * radio I/O completes.
     */
    suspend fun sendGroupMessage(groupId: String, plaintext: String)

    /** Marks every inbound message for [groupId] as read. */
    suspend fun markGroupAsRead(groupId: String)

    /** Drops a group and every membership / message row attached to it. */
    suspend fun deleteGroup(groupId: String)

    /**
     * Hook called by `MessageRepositoryImpl` when a peer's Signal
     * session transitions to SECURE. Drains any group invites or
     * group messages that were queued for the peer while the
     * handshake was pending.
     */
    suspend fun onPeerSessionSecure(macAddress: String)

    /**
     * Hook called by `MessageRepositoryImpl` when an inbound
     * `GROUP_INVITE` frame has been decrypted. The implementation
     * upserts the group + member rows locally so the chat list
     * picks the new group up automatically.
     *
     * @param fromMac      MAC of the peer that authored the invite
     *                     (typically the group owner).
     * @param plaintext    UTF-8 JSON body of the decrypted invite.
     */
    suspend fun handleIncomingGroupInvite(fromMac: String, plaintext: ByteArray)

    /**
     * Hook called by `MessageRepositoryImpl` when an inbound
     * `GROUP_MESSAGE` frame has been decrypted. The implementation
     * persists the resulting [GroupMessageEntity] locally.
     *
     * @param fromMac     MAC of the peer that authored the message.
     * @param senderName  Display name to attribute the message to.
     * @param plaintext   The raw plaintext as produced by
     *                    [com.example.bluewave_mobile.network.BlueWaveFrame.GroupMessageBody.encode].
     */
    suspend fun handleIncomingGroupMessage(
        fromMac: String,
        senderName: String,
        plaintext: ByteArray,
    )
}

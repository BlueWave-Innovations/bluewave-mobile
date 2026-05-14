package com.example.bluewave_mobile.data

import android.util.Log
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.SignalEngine
import com.example.bluewave_mobile.crypto.SignalEngineException
import com.example.bluewave_mobile.network.BlueWaveFrame
import com.example.bluewave_mobile.network.MessageTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Concrete implementation of [GroupRepository].
 *
 * Two collaborators do all of the heavy lifting:
 *
 *  * [chatGroupDao] is the persistence boundary. Every group / member /
 *    message lives in Room first and the UI subscribes through Room's
 *    invalidation tracker — this is what makes outgoing messages show
 *    up in the chat instantly even before the radio I/O completes.
 *  * [signalEngine] + [transport] are the wire layer. We encrypt every
 *    group payload under the sender's pairwise libsignal session with
 *    each recipient and fan out one frame per member; this gives us
 *    full E2EE without dragging the libsignal Sender Keys subsystem in.
 *
 * Two implementation details worth calling out:
 *
 *  * [pendingOps] is a per-peer queue of frames that could not be
 *    shipped immediately because there is no Signal session yet. The
 *    chat-list scheduler hooks call [onPeerSessionSecure] when the
 *    session transitions to SECURE; that drains the queue.
 *  * [opsMutex] coarsely serialises every mutation that touches
 *    [pendingOps]. The DAO is concurrency-safe on its own.
 *
 * @property chatGroupDao Room DAO backing the group taxonomy.
 * @property cryptoManager AES-256-GCM facade for encryption-at-rest
 *                          (mirrors [MessageRepositoryImpl]).
 * @property signalEngine Pairwise E2EE engine. Optional so JVM unit
 *                         tests that do not link libsignal can still
 *                         drive the legacy plaintext-on-wire path.
 * @property transport     Network primitive used to push bytes to
 *                         the peer. Optional for the same reason.
 * @property localMacProvider Returns the local device's uppercased MAC
 *                            (or "" when permissions are missing). Used
 *                            to label outbound rows and to filter the
 *                            local device out of fan-out broadcasts.
 * @property localNameProvider Returns the local device's display name
 *                              for outgoing messages.
 */
class GroupRepositoryImpl(
    private val chatGroupDao: ChatGroupDao,
    private val cryptoManager: CryptoManager = CryptoManager(),
    private val signalEngine: SignalEngine? = null,
    private val transport: MessageTransport? = null,
    private val localMacProvider: () -> String = { "" },
    private val localNameProvider: () -> String = { "Me" },
) : GroupRepository {

    private val opsMutex: Mutex = Mutex()

    /**
     * Per-peer queue of `(frameType, body)` pairs that were generated
     * locally but could not be shipped because no libsignal session
     * existed yet. Drained from [onPeerSessionSecure] when
     * `MessageRepositoryImpl` reports the session is up.
     */
    private val pendingOps: MutableMap<String, MutableList<PendingOp>> = HashMap()

    override fun observeGroups(): Flow<List<ChatGroupEntity>> {
        return chatGroupDao.observeGroups()
    }

    override fun observeAllMemberships(): Flow<List<GroupMemberEntity>> {
        return chatGroupDao.observeAllMemberships()
    }

    override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> {
        return chatGroupDao.observeMembers(groupId)
    }

    override fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>> {
        return chatGroupDao.observeMessages(groupId)
    }

    override suspend fun groupSummaries(): List<GroupSummary> {
        return chatGroupDao.groupSummaries()
    }

    override suspend fun findGroup(groupId: String): ChatGroupEntity? {
        return chatGroupDao.findGroup(groupId)
    }

    override suspend fun createGroup(name: String, memberMacs: List<String>): String {
        val groupId = UUID.randomUUID().toString().uppercase()
        val ownerMac = localMacProvider().uppercase()
        val now = System.currentTimeMillis()
        val normalizedMembers: List<String> = (memberMacs + ownerMac)
            .map { it.uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
        val group = ChatGroupEntity(
            id = groupId,
            name = name,
            ownerMac = ownerMac,
            createdAt = now,
        )
        chatGroupDao.upsertGroup(group)
        chatGroupDao.upsertMembers(
            normalizedMembers.map { mac ->
                GroupMemberEntity(groupId = groupId, peerMac = mac, joinedAt = now)
            },
        )
        // Fan out the invite to every member except ourselves. We
        // intentionally suppress every ship error here — invites that
        // can not be delivered now will be retried via
        // [onPeerSessionSecure] when the missing session comes up.
        val invitePayload = encodeInvite(group, normalizedMembers)
        val recipients = normalizedMembers.filter { it != ownerMac }
        for (peer in recipients) {
            shipGroupFrame(BlueWaveFrame.Type.GROUP_INVITE, invitePayload, peer)
        }
        return groupId
    }

    override suspend fun sendGroupMessage(groupId: String, plaintext: String) {
        val group = chatGroupDao.findGroup(groupId) ?: run {
            Log.w(TAG, "sendGroupMessage: unknown group $groupId")
            return
        }
        val ownerMac = localMacProvider().uppercase()
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val (iv, ciphertext) = cryptoManager.encrypt(plaintextBytes)
        // Persist the outgoing copy first — the UI subscribes to the
        // DB and re-renders before the network fan-out completes.
        chatGroupDao.insertMessage(
            GroupMessageEntity(
                groupId = groupId,
                senderMac = ownerMac,
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = true,
                senderName = localNameProvider(),
                isRead = true,
            ),
        )
        val members = chatGroupDao.memberMacs(groupId)
        val recipients = members.map { it.uppercase() }.filter { it != ownerMac }
        if (recipients.isEmpty()) return
        val body = BlueWaveFrame.GroupMessageBody.encode(group.id, plaintextBytes)
        for (peer in recipients) {
            shipGroupFrame(BlueWaveFrame.Type.GROUP_MESSAGE, body, peer)
        }
    }

    override suspend fun markGroupAsRead(groupId: String) {
        chatGroupDao.markAllRead(groupId)
    }

    override suspend fun deleteGroup(groupId: String) {
        chatGroupDao.deleteGroup(groupId)
    }

    override suspend fun onPeerSessionSecure(macAddress: String) {
        val key = macAddress.uppercase()
        val toShip: List<PendingOp> = opsMutex.withLock {
            pendingOps.remove(key) ?: return@withLock emptyList()
        }
        for (op in toShip) {
            shipGroupFrameNow(op.type, op.body, key)
        }
    }

    override suspend fun handleIncomingGroupInvite(fromMac: String, plaintext: ByteArray) {
        val invite = runCatching {
            JSON_FORMAT.decodeFromString(GroupInvitePayload.serializer(), plaintext.toString(Charsets.UTF_8))
        }.getOrNull() ?: run {
            Log.w(TAG, "Dropping malformed group invite from $fromMac")
            return
        }
        val now = System.currentTimeMillis()
        chatGroupDao.upsertGroup(
            ChatGroupEntity(
                id = invite.groupId,
                name = invite.name,
                ownerMac = invite.ownerMac.uppercase(),
                createdAt = invite.createdAt,
            ),
        )
        chatGroupDao.upsertMembers(
            invite.members.map { mac ->
                GroupMemberEntity(
                    groupId = invite.groupId,
                    peerMac = mac.uppercase(),
                    joinedAt = now,
                )
            },
        )
    }

    override suspend fun handleIncomingGroupMessage(
        fromMac: String,
        senderName: String,
        plaintext: ByteArray,
    ) {
        val inner = BlueWaveFrame.GroupMessageBody.decode(plaintext) ?: run {
            Log.w(TAG, "Dropping malformed group message body from $fromMac")
            return
        }
        // Encrypt-at-rest with the local AES key. The wire-level
        // libsignal envelope was already stripped one layer up — this
        // is the storage codec, not transport.
        val (iv, ciphertext) = cryptoManager.encrypt(inner.message)
        chatGroupDao.insertMessage(
            GroupMessageEntity(
                groupId = inner.groupId,
                senderMac = fromMac.uppercase(),
                encryptedPayload = ciphertext,
                iv = iv,
                isOutgoing = false,
                senderName = senderName,
                isRead = false,
            ),
        )
    }

    // ---------------------------------------------------------------
    // Wire fan-out helpers
    // ---------------------------------------------------------------

    /**
     * Encrypts [body] for [peerMac] under the existing pairwise
     * libsignal session and ships a [frameType] frame. If no session
     * exists yet the op is queued in [pendingOps] for later replay.
     */
    private suspend fun shipGroupFrame(
        frameType: BlueWaveFrame.Type,
        body: ByteArray,
        peerMac: String,
    ) {
        val engine = signalEngine
        val activeTransport = transport
        if (engine == null || activeTransport == null) {
            // Tests / legacy plaintext-on-wire mode: ship the body as
            // a raw GROUP_INVITE / GROUP_MESSAGE frame so the receiver
            // can still parse it. The unit tests that drive this path
            // do not run libsignal anyway.
            activeTransport?.send(peerMac, BlueWaveFrame.encode(frameType, body))
            return
        }
        if (!engine.hasSession(peerMac)) {
            opsMutex.withLock {
                pendingOps.getOrPut(peerMac) { ArrayList() }.add(PendingOp(frameType, body))
            }
            return
        }
        shipGroupFrameNow(frameType, body, peerMac)
    }

    /**
     * Encrypts and ships a group frame *without* the queue fallback.
     * Caller must have verified there is a SECURE session.
     */
    private suspend fun shipGroupFrameNow(
        frameType: BlueWaveFrame.Type,
        body: ByteArray,
        peerMac: String,
    ) {
        val engine = signalEngine ?: return
        val activeTransport = transport ?: return
        val ciphertext = try {
            engine.encrypt(peerMac, body)
        } catch (e: SignalEngineException) {
            Log.w(TAG, "Group encrypt failed for $peerMac: ${e.message}")
            return
        }
        val subtype = when (ciphertext.type) {
            SignalEngine.Ciphertext.Type.SIGNAL_MESSAGE ->
                BlueWaveFrame.GroupEnvelope.Subtype.SIGNAL_MESSAGE
            SignalEngine.Ciphertext.Type.PREKEY_SIGNAL_MESSAGE ->
                BlueWaveFrame.GroupEnvelope.Subtype.PREKEY_SIGNAL_MESSAGE
        }
        val envelope = BlueWaveFrame.GroupEnvelope.encode(subtype, ciphertext.bytes)
        val framed = BlueWaveFrame.encode(frameType, envelope)
        activeTransport.send(peerMac, framed)
    }

    private fun encodeInvite(group: ChatGroupEntity, members: List<String>): ByteArray {
        val payload = GroupInvitePayload(
            groupId = group.id,
            name = group.name,
            ownerMac = group.ownerMac,
            createdAt = group.createdAt,
            members = members,
        )
        return JSON_FORMAT.encodeToString(GroupInvitePayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Pending op queued in [pendingOps]. The body is the *plaintext*
     * bytes that will be wrapped in libsignal + envelope at ship time
     * — keeping the queue post-encryption would require us to know
     * the recipient's session up-front, which is exactly what we are
     * waiting on.
     */
    private data class PendingOp(val type: BlueWaveFrame.Type, val body: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingOp) return false
            return type == other.type && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * type.hashCode() + body.contentHashCode()
    }

    /**
     * JSON-serialisable payload for a [BlueWaveFrame.Type.GROUP_INVITE]
     * frame. Tolerant codec settings (`ignoreUnknownKeys = true`) let
     * future BlueWave versions extend the wire format without
     * breaking older peers.
     */
    @Serializable
    internal data class GroupInvitePayload(
        val groupId: String,
        val name: String,
        val ownerMac: String,
        val createdAt: Long,
        val members: List<String>,
    )

    private companion object {
        const val TAG = "GroupRepository"
        val JSON_FORMAT: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** No-op repository used for unit tests / preview / non-Bluetooth flows. */
class NoOpGroupRepository : GroupRepository {
    override fun observeGroups(): Flow<List<ChatGroupEntity>> = flowOf(emptyList())
    override fun observeAllMemberships(): Flow<List<GroupMemberEntity>> = flowOf(emptyList())
    override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> = flowOf(emptyList())
    override fun observeMessages(groupId: String): Flow<List<GroupMessageEntity>> = flowOf(emptyList())
    override suspend fun groupSummaries(): List<GroupSummary> = emptyList()
    override suspend fun findGroup(groupId: String): ChatGroupEntity? = null
    override suspend fun createGroup(name: String, memberMacs: List<String>): String = ""
    override suspend fun sendGroupMessage(groupId: String, plaintext: String) = Unit
    override suspend fun markGroupAsRead(groupId: String) = Unit
    override suspend fun deleteGroup(groupId: String) = Unit
    override suspend fun onPeerSessionSecure(macAddress: String) = Unit
    override suspend fun handleIncomingGroupInvite(fromMac: String, plaintext: ByteArray) = Unit
    override suspend fun handleIncomingGroupMessage(
        fromMac: String,
        senderName: String,
        plaintext: ByteArray,
    ) = Unit
}

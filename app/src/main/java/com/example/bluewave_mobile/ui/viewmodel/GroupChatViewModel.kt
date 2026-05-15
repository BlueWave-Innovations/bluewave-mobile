package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.data.ChatGroupEntity
import com.example.bluewave_mobile.data.GroupMemberEntity
import com.example.bluewave_mobile.data.GroupMessageEntity
import com.example.bluewave_mobile.data.GroupRepository
import com.example.bluewave_mobile.ui.intent.GroupChatIntent
import com.example.bluewave_mobile.ui.state.GroupChatMessage
import com.example.bluewave_mobile.ui.state.GroupChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the multi-peer group chat screen.
 *
 * Mirrors [ChatViewModel] but threads through [GroupRepository]
 * which is responsible for fanning every outgoing message out to
 * the group's members one libsignal session at a time.
 *
 * Three reactive sources are combined into a single
 * [GroupChatUiState] flow:
 *
 *  * [GroupRepository.observeMessages] decoded into [GroupChatMessage]
 *    via [CryptoManager] on [Dispatchers.Default];
 *  * [GroupRepository.observeMembers] for the membership roster
 *    rendered in the top bar;
 *  * [GroupRepository.observeGroups] is mined for the [ChatGroupEntity]
 *    matching [groupId] so the screen can render the localized name
 *    even after the group is renamed.
 *
 * Outgoing messages get an optimistic in-memory bubble that
 * disappears once the matching persisted row arrives through the
 * Room flow — the same pattern used by [ChatViewModel].
 */
class GroupChatViewModel(
    private val groupId: String,
    private val repository: GroupRepository,
    private val crypto: CryptoManager,
    private val localMacProvider: () -> String = { "" },
    private val localNameProvider: () -> String = { "Me" },
) : ViewModel() {

    private val optimistic: MutableStateFlow<List<GroupChatMessage>> = MutableStateFlow(emptyList())

    private val intents: MutableSharedFlow<GroupChatIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    /**
     * Public, lifecycle-friendly UI state. The upstream flow:
     *
     *  1. observes the persisted Room rows for [groupId];
     *  2. decrypts each on [Dispatchers.Default];
     *  3. merges any pending [optimistic] outgoing messages on top;
     *  4. wraps everything as a [GroupChatUiState.Success] alongside
     *     the latest membership roster + group metadata.
     */
    val uiState: StateFlow<GroupChatUiState> = combine(
        repository.observeMessages(groupId).map(::decryptAll).flowOn(Dispatchers.Default),
        repository.observeMembers(groupId),
        repository.observeGroups().map { groups -> groups.firstOrNull { it.id == groupId } },
        optimistic,
    ) { persisted, members, group, pending ->
        if (group == null) {
            return@combine GroupChatUiState.Error("Group not found") as GroupChatUiState
        }
        val merged: List<GroupChatMessage> = (persisted + pending)
            .distinctBy(GroupChatMessage::id)
            .sortedBy(GroupChatMessage::timestamp)
        GroupChatUiState.Success(
            group = group,
            members = members,
            messages = merged,
        ) as GroupChatUiState
    }
        .catch { throwable ->
            emit(GroupChatUiState.Error(throwable.message ?: "Failed to load group chat"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = GroupChatUiState.Loading,
        )

    init {
        viewModelScope.launch {
            intents.collect { intent ->
                when (intent) {
                    is GroupChatIntent.SendMessage -> sendMessage(intent.plaintext)
                    GroupChatIntent.Retry -> Unit
                }
            }
        }
        viewModelScope.launch {
            runCatching { repository.markGroupAsRead(groupId) }
        }
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: GroupChatIntent) {
        intents.tryEmit(intent)
    }

    private suspend fun sendMessage(plaintext: String) {
        if (plaintext.isBlank()) return
        val pendingId = -System.nanoTime()
        val pendingTimestamp = System.currentTimeMillis()
        val pending = GroupChatMessage(
            id = pendingId,
            text = plaintext,
            senderMac = localMacProvider().uppercase(),
            senderName = localNameProvider(),
            isOutgoing = true,
            timestamp = pendingTimestamp,
        )
        optimistic.update { it + pending }
        try {
            repository.sendGroupMessage(groupId, plaintext)
        } catch (cause: Exception) {
            optimistic.update { current ->
                current.map { item ->
                    if (item.id == pendingId) {
                        item.copy(isCorrupted = true, text = "")
                    } else {
                        item
                    }
                }
            }
        }
        // Drop the optimistic bubble once a persisted row with the
        // same plaintext lands. We can't compare by id because the
        // Room insert mints a fresh primary key.
        viewModelScope.launch {
            kotlinx.coroutines.delay(2_000L)
            optimistic.update { current ->
                current.filterNot { it.id == pendingId }
            }
        }
    }

    private fun decryptAll(entities: List<GroupMessageEntity>): List<GroupChatMessage> =
        entities.map(::toChatMessage)

    private fun toChatMessage(entity: GroupMessageEntity): GroupChatMessage {
        if (entity.iv.isEmpty()) {
            return GroupChatMessage(
                id = entity.id,
                text = "",
                senderMac = entity.senderMac,
                senderName = entity.senderName,
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
                isCorrupted = true,
            )
        }
        return when (val result = crypto.decrypt(entity.iv, entity.encryptedPayload)) {
            is DecryptionResult.Success -> GroupChatMessage(
                id = entity.id,
                text = result.plaintext.toString(Charsets.UTF_8),
                senderMac = entity.senderMac,
                senderName = entity.senderName,
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
            )
            is DecryptionResult.Tampered -> GroupChatMessage(
                id = entity.id,
                text = "",
                senderMac = entity.senderMac,
                senderName = entity.senderName,
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
                isCorrupted = true,
            )
        }
    }

    companion object {
        /** SavedStateHandle key for the active group id. */
        const val ARG_GROUP_ID: String = "groupId"

        /**
         * `ViewModelProvider.Factory` that pulls dependencies out of
         * the [com.example.bluewave_mobile.BlueWaveApplication.container].
         * Hosts must populate `SavedStateHandle["groupId"]` (the
         * Navigation Compose typed route does this automatically).
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                val handle: SavedStateHandle = createSavedStateHandle()
                val id: String = checkNotNull(handle[ARG_GROUP_ID]) {
                    "GroupChatViewModel requires SavedStateHandle[\"$ARG_GROUP_ID\"]"
                }
                val adapter = app.container.bluetoothAdapter
                GroupChatViewModel(
                    groupId = id,
                    repository = app.container.groupRepository,
                    crypto = app.container.cryptoManager,
                    localMacProvider = { adapter?.address?.uppercase().orEmpty() },
                    localNameProvider = {
                        val name = adapter?.name
                        if (name.isNullOrBlank()) "Me" else name
                    },
                )
            }
        }
    }
}

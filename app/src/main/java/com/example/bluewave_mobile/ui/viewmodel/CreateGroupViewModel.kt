package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.data.ConversationSummary
import com.example.bluewave_mobile.data.GroupRepository
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.PeerProfileEntity
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.ui.intent.CreateGroupIntent
import com.example.bluewave_mobile.ui.state.CreateGroupCandidate
import com.example.bluewave_mobile.ui.state.CreateGroupUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for [com.example.bluewave_mobile.ui.screens.CreateGroupScreen].
 *
 * Drives the create-group form (name field + member picker) and
 * routes the eventual [GroupRepository.createGroup] call.
 *
 * The candidate list is the union of:
 *
 *  * everyone the local device has chat history with — these peers
 *    already have an at-rest libsignal session, which means a
 *    `GROUP_INVITE` can ship immediately after the group is created;
 *  * every system-bonded radio peer — the user can target a freshly
 *    paired phone even when no message has been exchanged yet.
 *
 * Selections are stored as a `Set<String>` of uppercased MACs so
 * toggling a row is O(1).
 */
class CreateGroupViewModel(
    private val groupRepository: GroupRepository,
    private val messageRepository: MessageRepository,
    private val bluetoothDiscovery: BluetoothDiscovery,
) : ViewModel() {

    private val intents: MutableSharedFlow<CreateGroupIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    private val nameState: MutableStateFlow<String> = MutableStateFlow("")
    private val selectedMacs: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private val candidatePool: MutableStateFlow<List<CandidatePeer>> = MutableStateFlow(emptyList())

    private val _uiState: MutableStateFlow<CreateGroupUiState> =
        MutableStateFlow(CreateGroupUiState.Idle)

    val uiState: StateFlow<CreateGroupUiState> = combine(
        _uiState,
        nameState,
        selectedMacs,
        candidatePool,
    ) { phase, name, selected, peers ->
        when (phase) {
            is CreateGroupUiState.Created,
            is CreateGroupUiState.Error,
            -> phase
            is CreateGroupUiState.Submitting -> CreateGroupUiState.Submitting(
                name = name,
                candidates = peers.map { it.toCandidate(selected.contains(it.macAddress)) },
            )
            else -> CreateGroupUiState.Editing(
                name = name,
                candidates = peers.map { it.toCandidate(selected.contains(it.macAddress)) },
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = CreateGroupUiState.Idle,
    )

    init {
        viewModelScope.launch {
            // Eagerly compute the candidate pool from existing chats
            // + system-bonded peers + cached profile names. We don't
            // run a fresh discovery here — the user will already
            // see freshly-discovered peers from the device list and
            // can come back here once they're bonded.
            refreshCandidates()
        }
        viewModelScope.launch {
            intents.collect { intent ->
                when (intent) {
                    is CreateGroupIntent.UpdateName -> nameState.value = intent.name
                    is CreateGroupIntent.ToggleMember -> toggleMember(intent.macAddress)
                    CreateGroupIntent.Submit -> submit()
                }
            }
        }
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: CreateGroupIntent) {
        intents.tryEmit(intent)
    }

    private fun toggleMember(macAddress: String) {
        val mac = macAddress.uppercase()
        selectedMacs.update { current ->
            if (mac in current) current - mac else current + mac
        }
    }

    private suspend fun submit() {
        val name = nameState.value.trim()
        val members = selectedMacs.value.toList()
        if (name.isBlank() || members.isEmpty()) return
        _uiState.value = CreateGroupUiState.Submitting(
            name = name,
            candidates = candidatePool.value.map {
                it.toCandidate(selectedMacs.value.contains(it.macAddress))
            },
        )
        try {
            val groupId = withContext(Dispatchers.IO) {
                groupRepository.createGroup(name = name, memberMacs = members)
            }
            _uiState.value = CreateGroupUiState.Created(groupId = groupId)
        } catch (cause: Exception) {
            _uiState.value = CreateGroupUiState.Error(
                message = cause.message ?: "Couldn't create the group",
            )
        }
    }

    private suspend fun refreshCandidates() {
        val conversations: List<ConversationSummary> =
            runCatching { messageRepository.observeAllConversations().first() }
                .getOrDefault(emptyList())
        val profiles: List<PeerProfileEntity> =
            runCatching { messageRepository.observeAllPeerProfiles().first() }
                .getOrDefault(emptyList())
        val bonded: List<BluetoothDeviceInfo> = runCatching {
            bluetoothDiscovery.bondedDevices()
        }.getOrDefault(emptyList())

        val byMac: LinkedHashMap<String, CandidatePeer> = LinkedHashMap()
        // Peers we've already chatted with first — likely groups want
        // to include those before brand-new bonded devices.
        for (summary in conversations) {
            val mac = summary.macAddress.uppercase()
            val name = profiles.firstOrNull { it.macAddress.equals(mac, ignoreCase = true) }
                ?.displayName
                ?.takeUnless(String::isBlank)
                ?: summary.lastMessage.senderName.takeUnless(String::isBlank)
                ?: mac
            byMac[mac] = CandidatePeer(macAddress = mac, displayName = name)
        }
        for (peer in bonded) {
            val mac = peer.macAddress.uppercase()
            if (byMac.containsKey(mac)) continue
            val name = profiles.firstOrNull { it.macAddress.equals(mac, ignoreCase = true) }
                ?.displayName
                ?.takeUnless(String::isBlank)
                ?: peer.name.takeUnless(String::isBlank)
                ?: mac
            byMac[mac] = CandidatePeer(macAddress = mac, displayName = name)
        }
        candidatePool.value = byMac.values
            .toList()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        if (_uiState.value == CreateGroupUiState.Idle) {
            _uiState.value = CreateGroupUiState.Editing(
                name = "",
                candidates = candidatePool.value.map { it.toCandidate(false) },
            )
        }
    }

    /** Internal helper — the picker wires `displayName` and the
     *  selection flag together when projecting the snapshot.
     */
    private data class CandidatePeer(
        val macAddress: String,
        val displayName: String,
    ) {
        fun toCandidate(selected: Boolean): CreateGroupCandidate = CreateGroupCandidate(
            macAddress = macAddress,
            displayName = displayName,
            selected = selected,
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                CreateGroupViewModel(
                    groupRepository = app.container.groupRepository,
                    messageRepository = app.container.messageRepository,
                    bluetoothDiscovery = app.container.bluetoothDiscovery,
                )
            }
        }
    }
}

private fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val current = value
        val next = transform(current)
        if (compareAndSet(current, next)) return
    }
}

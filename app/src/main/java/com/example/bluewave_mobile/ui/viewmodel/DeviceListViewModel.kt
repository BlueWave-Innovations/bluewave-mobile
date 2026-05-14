package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.data.ChatFolderEntity
import com.example.bluewave_mobile.data.ChatGroupEntity
import com.example.bluewave_mobile.data.ConversationSummary
import com.example.bluewave_mobile.data.FolderRepository
import com.example.bluewave_mobile.data.GroupMemberEntity
import com.example.bluewave_mobile.data.GroupRepository
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.PeerFolderAssignmentEntity
import com.example.bluewave_mobile.data.PeerProfileEntity
import com.example.bluewave_mobile.network.ApkSender
import com.example.bluewave_mobile.network.BlueWaveSdpProber
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.network.MessageTransport
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.model.ContactRow
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel that drives the sectioned device-list screen.
 *
 * The reducer receives a [DeviceListIntent] through [handleIntent],
 * mutates [_uiState] and (for scans) collects the cold combine of:
 *
 *  * [BluetoothDiscovery.bondedDevices] / [BluetoothDiscovery.discoverDevices]
 *    — peers visible on the radio right now;
 *  * [MessageRepository.observeAllConversations] — peers we already
 *    have persisted chat history with;
 *  * [BlueWaveSdpProber.appPresence] — whether each visible peer's
 *    SDP record advertises the BlueWave service UUID.
 *
 * The combined snapshot is reshaped into a flat
 * `List<ContactRow>` whose subtypes drive the three on-screen
 * sections — see [ContactRow] kdoc.
 *
 * **`stateIn` semantics.** A naive ViewModel would expose
 * `_uiState.asStateFlow()` directly, which keeps the discovery flow
 * collected even after the screen leaves composition (e.g. the user
 * rotates the device or backgrounds the app). Wrapping with
 * `stateIn(SharingStarted.WhileSubscribed(5_000), Idle)` gives us:
 *
 *  * Configuration changes do **not** cancel the scan, so flipping
 *    the device 180° doesn't drop the partial list — we keep the
 *    upstream live for 5 s of "no subscribers".
 *  * Putting the app in the background eventually cancels the flow
 *    once the 5 s grace window elapses, releasing the radio chipset
 *    (a non-trivial battery saving on Android 16).
 *
 * Constructor takes the collaborators rather than the singleton
 * [BlueWaveApplication.container] so unit tests can drop in fakes
 * without touching the global container.
 *
 * @property crypto Optional handle used to decrypt the *last message*
 *                  preview shown next to each chat row. Decryption
 *                  runs lazily — if the call returns a tampered
 *                  result the preview falls back to a localized
 *                  placeholder so the row stays renderable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModel(
    private val bluetoothDiscovery: BluetoothDiscovery,
    private val messageRepository: MessageRepository,
    private val sdpProber: BlueWaveSdpProber,
    private val apkSender: ApkSender,
    private val folderRepository: FolderRepository? = null,
    private val groupRepository: GroupRepository? = null,
    private val crypto: CryptoManager? = null,
    private val transport: MessageTransport? = null,
) : ViewModel() {

    private val intents: MutableSharedFlow<DeviceListIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    private val _uiState: MutableStateFlow<DeviceListUiState> = MutableStateFlow(DeviceListUiState.Idle)

    /**
     * Currently-active folder filter. `null` means the synthetic
     * "All chats" chip — every row is rendered. Any non-null value
     * is the literal [ChatFolderEntity.id] of a built-in or
     * user-created folder.
     */
    private val _selectedFolderId: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * One-shot signals fired by intents that don't directly mutate
     * [uiState] but still need to surface a confirmation to the screen
     * (e.g. "system Bluetooth share couldn't be opened").
     */
    private val _events: MutableSharedFlow<DeviceListEvent> = MutableSharedFlow(extraBufferCapacity = 4)

    /** External event channel mirroring [DeviceListEvent]. */
    val events: Flow<DeviceListEvent> = _events

    /**
     * Public, lifecycle-friendly snapshot of the screen state. Wrapped
     * with `stateIn` so the upstream collection survives short-lived
     * unsubscriptions (configuration changes) but is released after
     * 5 s of true inactivity.
     */
    val uiState: StateFlow<DeviceListUiState> = _uiState
        .asStateFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DeviceListUiState.Idle,
        )

    /**
     * Live list of every folder the user can filter by, ordered for
     * chip-row display. Empty when [folderRepository] is `null` —
     * unit tests that don't care about folders take that branch.
     */
    val availableFolders: StateFlow<List<ChatFolderEntity>> =
        (folderRepository?.observeFolders() ?: flowOf(emptyList()))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )

    /** Currently-active folder filter; `null` means "All chats". */
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    init {
        viewModelScope.launch {
            intents
                .flatMapLatest { intent -> reduce(intent) }
                .catch { throwable ->
                    emit(DeviceListUiState.Error(throwable.message ?: "Discovery failed"))
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
        // When a peer opens an RFCOMM session to us (or we to them),
        // that is definitive proof that BlueWave is running on their
        // side — mark the MAC as present so the SDP prober's stale
        // cache does not keep the peer stuck in "Install suggestion".
        transport?.let { t ->
            viewModelScope.launch {
                t.sessionAttached.collect { mac ->
                    sdpProber.markPresent(mac)
                }
            }
        }
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: DeviceListIntent) {
        intents.tryEmit(intent)
    }

    /**
     * Pin the chip-row selection. Pass `null` for the synthetic
     * "All chats" chip. Any other value MUST be the
     * [ChatFolderEntity.id] of a folder the user has created or
     * that we seeded as built-in.
     *
     * Folder selection lives outside the [DeviceListIntent] reducer
     * because we don't want flipping the chip to cancel an in-flight
     * scan — the active scan picks the new filter up automatically
     * through its `combine` upstream and re-emits a [Scanning] state
     * with the filtered rows.
     */
    fun setFolder(folderId: String?) {
        _selectedFolderId.value = folderId
    }

    /**
     * Pure-function reducer: maps an [intent] to a [Flow] of
     * [DeviceListUiState] emissions. Returning a flow (instead of a
     * single state) lets [DeviceListIntent.StartScan] stream
     * intermediate `Scanning(rows)` snapshots while discovery and DB
     * observation are still live.
     */
    private fun reduce(intent: DeviceListIntent): Flow<DeviceListUiState> = when (intent) {
        DeviceListIntent.StartScan -> scanFlow()
        DeviceListIntent.StopScan -> {
            val current = _uiState.value
            val rows = if (current is DeviceListUiState.Scanning) current.rows else emptyList()
            flowOf(DeviceListUiState.Loaded(rows))
        }
        DeviceListIntent.PermissionsGranted -> flowOf(DeviceListUiState.Idle)
        is DeviceListIntent.DeviceSelected -> {
            // Fire-and-forget: marking-as-read is a side effect that
            // should not gate the navigation — the screen will fire
            // the navigation callback regardless.
            viewModelScope.launch {
                runCatching { messageRepository.markPeerAsRead(intent.macAddress) }
            }
            flowOf(_uiState.value) // pure navigation; state unchanged
        }
        is DeviceListIntent.SuggestInstall -> {
            viewModelScope.launch {
                val outcome = apkSender.suggestInstall()
                _events.emit(
                    if (outcome.isSuccess) {
                        DeviceListEvent.InstallSuggested(intent.macAddress)
                    } else {
                        DeviceListEvent.InstallSuggestionFailed(intent.macAddress)
                    },
                )
            }
            flowOf(_uiState.value)
        }
    }

    /**
     * Cold flow that performs a single discovery cycle while
     * combining radio-side observations with the live conversation
     * roster, emitting `Scanning(rows)` snapshots while discovery is
     * live and a `BluetoothDisabled` / `Error` state on faults.
     *
     * Implementation note: `radioPeers` is itself a flow that
     * synchronously collects bonded devices, then collects
     * `discoverDevices()` inline. Wiring it into the [combine] this
     * way means any exception thrown by the radio (e.g. an
     * `IllegalStateException` when Bluetooth is disabled) propagates
     * straight into the `catch` operator chained on the result —
     * we don't need a side-effect [Job] to forward errors.
     */
    private fun scanFlow(): Flow<DeviceListUiState> {
        sdpProber.start()
        val seen: MutableMap<String, BluetoothDeviceInfo> = LinkedHashMap()
        // `callbackFlow` exposes a coroutine scope that is tied to
        // the lifetime of the downstream collector, so the periodic
        // SDP re-probe job below is automatically cancelled when the
        // device list stops scanning. The ticker is what keeps the
        // "no BlueWave on this device anymore" detection working
        // after a peer uninstalls the app: Android caches SDP
        // results, so we have to ask for a fresh
        // `fetchUuidsWithSdp` every ~15 s for every bonded MAC. The
        // `appPresence` map gets refreshed via the broadcast
        // receiver inside [BlueWaveSdpProber] which then flows back
        // through the outer `combine` and re-emits new rows.
        val radioPeers: Flow<Map<String, BluetoothDeviceInfo>> = callbackFlow {
            // The re-probe ticker uses [Dispatchers.IO] (not the
            // caller's dispatcher) on purpose: in unit tests the
            // caller is a `TestScope` whose `delay` is virtual time,
            // and a `while (isActive) { delay(15s); … }` loop on
            // virtual time spins millions of iterations per second
            // until the JVM heap fills up. IO uses real wall-clock
            // time, so the loop is dormant for the entirety of a
            // <1 s unit-test run and only kicks in for a real
            // foreground device-list session on a phone.
            val reprobeJob = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(SDP_REPROBE_INTERVAL_MS)
                    // Snapshot the keys so a concurrent discovery
                    // emit can't trip a ConcurrentModificationException
                    // mid-iteration.
                    val macs = seen.keys.toList()
                    for (mac in macs) {
                        sdpProber.probe(mac)
                    }
                }
            }
            bluetoothDiscovery.bondedDevices().forEach { peer ->
                seen[peer.macAddress.uppercase()] = peer
                sdpProber.probe(peer.macAddress)
            }
            trySend(seen.toMap())
            launch {
                bluetoothDiscovery
                    .discoverDevices()
                    .onEach { peer ->
                        val mac = peer.macAddress.uppercase()
                        seen[mac] = peer
                        sdpProber.probe(peer.macAddress)
                    }
                    .collect { trySend(seen.toMap()) }
                trySend(seen.toMap())
            }
            awaitClose { reprobeJob.cancel() }
        }

        // Pre-roll the folder filter signals so a missing repository
        // (unit-test build) collapses to "no filter" rather than a
        // never-emitting flow that would stall the combine.
        val assignments: Flow<List<PeerFolderAssignmentEntity>> =
            folderRepository?.observeAssignments() ?: flowOf(emptyList())
        val folderFilter: Flow<FolderFilter> = combine(
            _selectedFolderId,
            assignments,
        ) { id, list ->
            val byMac: Map<String, Set<String>> = list
                .groupBy { it.peerId.uppercase() }
                .mapValues { (_, rows) -> rows.mapTo(HashSet()) { it.folderId } }
            FolderFilter(selectedFolderId = id, peerToFolders = byMac)
        }

        val groups: Flow<List<ChatGroupEntity>> =
            groupRepository?.observeGroups() ?: flowOf(emptyList())
        val memberships: Flow<List<GroupMemberEntity>> =
            groupRepository?.observeAllMemberships() ?: flowOf(emptyList())
        val groupSnapshot: Flow<GroupSnapshot> = combine(groups, memberships) { gs, ms ->
            GroupSnapshot(groups = gs, memberships = ms)
        }

        return combine(
            combine(
                radioPeers,
                messageRepository.observeAllConversations(),
                sdpProber.appPresence,
                messageRepository.observeAllPeerProfiles(),
            ) { peers, conversations, presence, peerProfiles ->
                RadioSnapshot(
                    peers = peers,
                    conversations = conversations,
                    presence = presence,
                    peerProfiles = peerProfiles,
                )
            },
            folderFilter,
            groupSnapshot,
        ) { radio, filter, groupBundle ->
            buildRows(
                peers = radio.peers,
                conversations = radio.conversations,
                presence = radio.presence,
                peerProfiles = radio.peerProfiles,
                selectedFolderId = filter.selectedFolderId,
                peerToFolders = filter.peerToFolders,
                groups = groupBundle.groups,
                memberships = groupBundle.memberships,
            )
        }
            .map<List<ContactRow>, DeviceListUiState> { rows -> DeviceListUiState.Scanning(rows) }
            .catch { throwable ->
                if (throwable is IllegalStateException) {
                    emit(DeviceListUiState.BluetoothDisabled)
                } else {
                    throw throwable
                }
            }
    }

    /** Snapshot of the current folder-filter state, fed into [buildRows]. */
    private data class FolderFilter(
        val selectedFolderId: String?,
        val peerToFolders: Map<String, Set<String>>,
    )

    /** Snapshot of every radio + DB observation collapsed for [buildRows]. */
    private data class RadioSnapshot(
        val peers: Map<String, BluetoothDeviceInfo>,
        val conversations: List<ConversationSummary>,
        val presence: Map<String, Boolean>,
        val peerProfiles: List<PeerProfileEntity>,
    )

    /** Snapshot of every group + membership row, fed into [buildRows]. */
    private data class GroupSnapshot(
        val groups: List<ChatGroupEntity>,
        val memberships: List<GroupMemberEntity>,
    )

    /**
     * Pure projection: combine the input flows into the flat
     * sectioned list consumed by the screen.
     *
     *  * [peers] — MAC → metadata for everything visible on the radio
     *    right now (bonded + freshly-discovered).
     *  * [conversations] — DB-backed roster of peers we already have
     *    persisted chat history with.
     *  * [presence] — MAC → did we observe the BlueWave UUID in the
     *    SDP record yet? Missing keys mean "not probed yet".
     *  * [peerProfiles] — cached profile cards pushed to us by peers
     *    over the `PROFILE_METADATA` Bluetooth frame; takes
     *    precedence over the radio-side device name when populated
     *    so the chat list shows the user-set "Алекс Иванов" rather
     *    than the OS-side device alias.
     *  * [selectedFolderId] / [peerToFolders] — chip-row filter; see
     *    [setFolder] kdoc. `null` means "All chats" (no filter); any
     *    other value drops every section except chats and keeps only
     *    chats whose peer is in the folder.
     *
     * Visible, "BlueWave-on-board" peers without history go to the
     * "Can start chat" section. Visible peers without the BlueWave
     * UUID go to the "No app yet" section. Peers that are off-radio
     * but show up in the conversation roster still appear in the
     * "Chats" section so old conversations remain reachable.
     */
    internal fun buildRows(
        peers: Map<String, BluetoothDeviceInfo>,
        conversations: List<ConversationSummary>,
        presence: Map<String, Boolean>,
        peerProfiles: List<PeerProfileEntity> = emptyList(),
        selectedFolderId: String? = null,
        peerToFolders: Map<String, Set<String>> = emptyMap(),
        groups: List<ChatGroupEntity> = emptyList(),
        memberships: List<GroupMemberEntity> = emptyList(),
    ): List<ContactRow> {
        val profilesByMac: Map<String, PeerProfileEntity> =
            peerProfiles.associateBy { it.macAddress.uppercase() }
        val chatMacs: MutableSet<String> = HashSet()
        val chatRows: MutableList<ContactRow.ExistingChat> = ArrayList(conversations.size)

        for (summary in conversations) {
            val mac = summary.macAddress.uppercase()
            chatMacs += mac
            val profileName = profilesByMac[mac]?.displayName?.takeUnless(String::isBlank)
            val displayName: String = profileName
                ?: peers[mac]?.name?.takeUnless(String::isBlank)
                ?: summary.lastMessage.senderName.takeUnless(String::isBlank)
                ?: mac
            chatRows += ContactRow.ExistingChat(
                displayName = displayName,
                macAddress = mac,
                lastMessagePreview = decryptPreview(summary),
                lastMessageTimestamp = summary.lastMessage.timestamp,
                unreadCount = summary.unreadCount,
                rssi = peers[mac]?.rssi,
            )
        }

        val candidateRows: MutableList<ContactRow.StartChatCandidate> = ArrayList()
        val installRows: MutableList<ContactRow.InstallSuggestion> = ArrayList()

        for ((mac, peer) in peers) {
            if (mac in chatMacs) continue
            val profileName = profilesByMac[mac]?.displayName?.takeUnless(String::isBlank)
            val name: String = profileName ?: peer.name.ifBlank { mac }
            val hasApp: Boolean? = presence[mac]
            // Decision matrix for the section a non-chat peer lands in:
            //
            //  * already bonded by the user                → candidate
            //    – the user explicitly paired this device, so we
            //      trust the bond. Falsely routing them into the
            //      "no app yet" section was the asymmetric chat /
            //      install-suggestion regression we saw on-device:
            //      Android's SDP cache for a bonded peer can stay
            //      stale forever if the cache was populated
            //      *before* the peer's accept loop came online.
            //      Even if BlueWave is genuinely absent the
            //      tap-to-chat flow falls back to a connect error
            //      and we can prompt then.
            //  * SDP says yes                              → candidate
            //  * SDP says no, unpaired                     → install suggestion
            //  * everything else (probe pending)           → candidate
            //    (optimistic; flips to install-suggest only when
            //    a definitive `false` lands AND the peer is
            //    unpaired).
            val keepAsCandidate: Boolean =
                peer.isPaired || hasApp == true || hasApp == null
            if (keepAsCandidate) {
                candidateRows += ContactRow.StartChatCandidate(
                    displayName = name,
                    macAddress = mac,
                    isBonded = peer.isPaired,
                    rssi = peer.rssi,
                )
            } else {
                installRows += ContactRow.InstallSuggestion(
                    displayName = name,
                    macAddress = mac,
                )
            }
        }

        // Build the group section. Each group row joins:
        //  * [ChatGroupEntity] for the display name + creation time,
        //  * the membership rows so we can show "4 участника" without
        //    issuing extra queries,
        //  * the latest [GroupMessageEntity] read off the matching
        //    `groupId` if one is staged via the same flow — for now we
        //    leave the preview empty because the group screen owns
        //    that summary; the row still re-renders on every group
        //    addition / removal.
        val membersByGroup: Map<String, Int> =
            memberships.groupingBy(GroupMemberEntity::groupId).eachCount()
        val groupRows: MutableList<ContactRow.GroupChat> = ArrayList(groups.size)
        for (group in groups) {
            val memberCount = (membersByGroup[group.id] ?: 0) + 1 // +1 for the local device
            groupRows += ContactRow.GroupChat(
                displayName = group.name.ifBlank { group.id },
                groupId = group.id,
                memberCount = memberCount,
                lastMessagePreview = "",
                lastMessageTimestamp = group.createdAt,
                unreadCount = 0,
            )
        }

        // Sort each section locally — chats by recency, candidates by
        // bonded-first then alphabetical, install suggestions
        // alphabetically. The screen renders sections in the order
        // [groups, chats, candidates, installs] and uses the row
        // subtype as the section divider.
        groupRows.sortByDescending(ContactRow.GroupChat::lastMessageTimestamp)
        chatRows.sortByDescending(ContactRow.ExistingChat::lastMessageTimestamp)
        candidateRows.sortWith(
            compareByDescending(ContactRow.StartChatCandidate::isBonded)
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
        )
        installRows.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

        // Apply the active chip filter. "All" leaves every row in
        // place; any other folder id keeps only chats whose peer sits
        // in that folder — the candidate and install sections are
        // hidden because peers without chat history can't be assigned
        // to folders yet.
        val filteredGroups: List<ContactRow.GroupChat>
        val filteredChats: List<ContactRow.ExistingChat>
        val filteredCandidates: List<ContactRow.StartChatCandidate>
        val filteredInstalls: List<ContactRow.InstallSuggestion>
        if (selectedFolderId == null) {
            filteredGroups = groupRows
            filteredChats = chatRows
            filteredCandidates = candidateRows
            filteredInstalls = installRows
        } else {
            filteredGroups = emptyList()
            filteredChats = chatRows.filter { row ->
                peerToFolders[row.macAddress.uppercase()]
                    ?.contains(selectedFolderId) == true
            }
            filteredCandidates = emptyList()
            filteredInstalls = emptyList()
        }

        // The result is concatenated in render order so a `LazyColumn`
        // can iterate without a secondary group-by pass.
        val combined: MutableList<ContactRow> = ArrayList(
            filteredGroups.size + filteredChats.size + filteredCandidates.size + filteredInstalls.size,
        )
        combined += filteredGroups
        combined += filteredChats
        combined += filteredCandidates
        combined += filteredInstalls
        return combined
    }

    private fun decryptPreview(summary: ConversationSummary): String {
        val crypto = this.crypto ?: return ""
        val entity = summary.lastMessage
        if (entity.iv.isEmpty() || entity.encryptedPayload.isEmpty()) return ""
        return when (val result = crypto.decrypt(entity.iv, entity.encryptedPayload)) {
            is DecryptionResult.Success -> result.plaintext.toString(Charsets.UTF_8)
            is DecryptionResult.Tampered -> ""
        }
    }

    /**
     * One-shot side-effect events surfaced by the reducer. Use
     * [events] to subscribe.
     */
    sealed interface DeviceListEvent {
        /** System bluetooth-share dialog was successfully launched. */
        data class InstallSuggested(val macAddress: String) : DeviceListEvent

        /** No activity was available to handle the APK share intent. */
        data class InstallSuggestionFailed(val macAddress: String) : DeviceListEvent
    }

    companion object {
        /**
         * Cadence of the periodic SDP re-probe loop. Android caches
         * SDP results internally, so to notice that a peer has
         * uninstalled BlueWave we have to ask
         * `fetchUuidsWithSdp` again on a timer. 15 s strikes a
         * balance between "user sees the row flip to download
         * suggestion within ~15 s of the uninstall" and "we don't
         * burn battery hammering the radio".
         */
        internal const val SDP_REPROBE_INTERVAL_MS: Long = 15_000L

        /**
         * `ViewModelProvider.Factory` that pulls dependencies out of
         * the [BlueWaveApplication.container]. Compose host:
         *
         * ```kotlin
         * val vm: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory)
         * ```
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                DeviceListViewModel(
                    bluetoothDiscovery = app.container.bluetoothDiscovery,
                    messageRepository = app.container.messageRepository,
                    sdpProber = app.container.sdpProber,
                    apkSender = app.container.apkSender,
                    folderRepository = app.container.folderRepository,
                    groupRepository = app.container.groupRepository,
                    crypto = app.container.cryptoManager,
                    transport = app.container.bluetoothSessionManager,
                )
            }
        }
    }
}

/**
 * Extracts the [BlueWaveApplication] from `CreationExtras`. Throws
 * with a precise message if the ViewModel is created outside of an
 * Activity / Application context (e.g. in a unit test that forgot to
 * supply `APPLICATION_KEY`).
 */
internal fun CreationExtras.application(): BlueWaveApplication =
    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
        "ViewModel created outside of an Activity; APPLICATION_KEY is null"
    } as BlueWaveApplication

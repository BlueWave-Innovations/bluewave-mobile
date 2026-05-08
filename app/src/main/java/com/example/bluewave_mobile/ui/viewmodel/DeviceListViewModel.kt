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
import com.example.bluewave_mobile.data.ConversationSummary
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.PeerProfileEntity
import com.example.bluewave_mobile.network.ApkSender
import com.example.bluewave_mobile.network.BlueWaveSdpProber
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.model.ContactRow
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    private val crypto: CryptoManager? = null,
) : ViewModel() {

    private val intents: MutableSharedFlow<DeviceListIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    private val _uiState: MutableStateFlow<DeviceListUiState> = MutableStateFlow(DeviceListUiState.Idle)

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
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: DeviceListIntent) {
        intents.tryEmit(intent)
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
        val radioPeers: Flow<Map<String, BluetoothDeviceInfo>> = flow {
            bluetoothDiscovery.bondedDevices().forEach { peer ->
                seen[peer.macAddress.uppercase()] = peer
                sdpProber.probe(peer.macAddress)
            }
            emit(seen.toMap())
            bluetoothDiscovery
                .discoverDevices()
                .onEach { peer ->
                    val mac = peer.macAddress.uppercase()
                    seen[mac] = peer
                    sdpProber.probe(peer.macAddress)
                }
                .collect { emit(seen.toMap()) }
            emit(seen.toMap())
        }

        return combine(
            radioPeers,
            messageRepository.observeAllConversations(),
            sdpProber.appPresence,
            messageRepository.observeAllPeerProfiles(),
        ) { peers, conversations, presence, peerProfiles ->
            buildRows(peers, conversations, presence, peerProfiles)
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

    /**
     * Pure projection: combine the four input flows into the flat
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
                isOnline = peers.containsKey(mac),
            )
        }

        val candidateRows: MutableList<ContactRow.StartChatCandidate> = ArrayList()
        val installRows: MutableList<ContactRow.InstallSuggestion> = ArrayList()

        for ((mac, peer) in peers) {
            if (mac in chatMacs) continue
            val profileName = profilesByMac[mac]?.displayName?.takeUnless(String::isBlank)
            val name: String = profileName ?: peer.name.ifBlank { mac }
            val hasApp: Boolean? = presence[mac]
            if (hasApp == true) {
                candidateRows += ContactRow.StartChatCandidate(
                    displayName = name,
                    macAddress = mac,
                    isBonded = peer.isPaired,
                )
            } else if (hasApp == false) {
                installRows += ContactRow.InstallSuggestion(
                    displayName = name,
                    macAddress = mac,
                )
            } else {
                // SDP record not yet resolved — assume the peer might
                // run BlueWave to keep the row in the "can start chat"
                // section while the probe is in flight. The row will
                // re-route to "no app yet" automatically once the
                // negative answer lands through `presence`.
                candidateRows += ContactRow.StartChatCandidate(
                    displayName = name,
                    macAddress = mac,
                    isBonded = peer.isPaired,
                )
            }
        }

        // Sort each section locally — chats by recency, candidates by
        // bonded-first then alphabetical, install suggestions
        // alphabetically. The screen renders sections in the order
        // [chats, candidates, installs] and uses the row subtype as
        // the section divider.
        chatRows.sortByDescending(ContactRow.ExistingChat::lastMessageTimestamp)
        candidateRows.sortWith(
            compareByDescending(ContactRow.StartChatCandidate::isBonded)
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName },
        )
        installRows.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

        // The result is concatenated in render order so a `LazyColumn`
        // can iterate without a secondary group-by pass.
        val combined: MutableList<ContactRow> = ArrayList(
            chatRows.size + candidateRows.size + installRows.size,
        )
        combined += chatRows
        combined += candidateRows
        combined += installRows
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
                    crypto = app.container.cryptoManager,
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

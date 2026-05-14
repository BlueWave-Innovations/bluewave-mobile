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
import com.example.bluewave_mobile.data.E2EEState
import com.example.bluewave_mobile.data.MessageEntity
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.MessageRepositoryImpl
import com.example.bluewave_mobile.data.PeerProfileEntity
import com.example.bluewave_mobile.network.MessageTransport
import com.example.bluewave_mobile.ui.intent.ChatIntent
import com.example.bluewave_mobile.ui.state.ChatMessage
import com.example.bluewave_mobile.ui.state.ChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the per-peer chat screen.
 *
 * Responsibilities:
 *  * Subscribe to [MessageRepository.getMessagesByDevice] for the
 *    active [deviceMac] and decrypt each item via [CryptoManager] on
 *    [Dispatchers.Default] (decryption is CPU-bound; running it on
 *    the main thread for the first few messages is fine but a long
 *    history would jank the LazyColumn).
 *  * Maintain an *optimistic UI* list of in-flight outgoing messages
 *    so the user sees their bubble appear instantly when they tap
 *    Send, even before the encrypt → persist → transmit pipeline
 *    inside [MessageRepository.sendMessage] has produced a Room
 *    insert. The optimistic items disappear automatically as soon as
 *    the corresponding persisted [MessageEntity] reaches us via the
 *    Room flow.
 *  * Expose a single [uiState] [StateFlow] in the MVI shape declared
 *    by [ChatUiState], lifecycle-scoped through
 *    `stateIn(WhileSubscribed(5_000))` to survive configuration
 *    changes without keeping the DB collection alive while
 *    backgrounded.
 *
 * `deviceMac` is read out of the [SavedStateHandle] populated by the
 * Navigation Compose typed route, which guarantees the value
 * survives process death without any custom serialisation.
 */
class ChatViewModel(
    private val deviceMac: String,
    private val repository: MessageRepository,
    private val crypto: CryptoManager,
    private val transport: MessageTransport? = null,
) : ViewModel() {

    /**
     * Optimistic in-memory list of outgoing messages that have been
     * accepted from the user but have not yet been observed in the
     * Room flow. Each entry uses a *negative* id so it never collides
     * with auto-generated row ids (which are positive `Long`s).
     */
    private val optimistic: MutableStateFlow<List<ChatMessage>> = MutableStateFlow(emptyList())

    private val intents: MutableSharedFlow<ChatIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    /**
     * Public, lifecycle-friendly UI state. The upstream flow:
     *
     *  1. observes the persisted Room rows for [deviceMac];
     *  2. decrypts each on [Dispatchers.Default];
     *  3. merges any pending [optimistic] outgoing messages on top;
     *  4. wraps everything as a [ChatUiState.Success].
     *
     * Errors from the DB / decryption pipeline collapse into
     * [ChatUiState.Error]; the screen surfaces a retry CTA that
     * dispatches [ChatIntent.Retry].
     */
    val uiState: StateFlow<ChatUiState> = combine(
        repository.getMessagesByDevice(deviceMac).map(::decryptAll).flowOn(Dispatchers.Default),
        repository.observeSessionState(deviceMac).distinctUntilChanged(),
    ) { persisted: List<ChatMessage>, e2eeState: E2EEState ->
        val persistedTexts = persisted
            .filter { it.isOutgoing }
            .mapTo(HashSet(), ChatMessage::text)
        val dedupedOptimistic = optimistic.value
            .filterNot { it.text in persistedTexts }
        val merged = (persisted + dedupedOptimistic)
            .sortedBy(ChatMessage::timestamp)
        ChatUiState.Success(
            messages = merged.map(::toEntityShim),
            isPeerPaused = isPaused(),
            e2eeState = e2eeState,
        ) as ChatUiState
    }
        .catch { throwable ->
            emit(ChatUiState.Error(throwable.message ?: "Failed to load chat history"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ChatUiState.Loading,
        )

    /**
     * Reactive view of the peer's last-seen profile card. Emits
     * `null` until the first `PROFILE_METADATA` frame from the
     * peer has been received and persisted; the chat top bar
     * gracefully falls back to the [deviceMac] in that case.
     */
    val peerProfile: StateFlow<PeerProfileEntity?> = repository
        .observePeerProfile(deviceMac)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    /**
     * Auto-hiding visibility flag for the bond-loss banner.
     *
     * The raw `isPeerPaused` flag inside [uiState] flips on every
     * `ACTION_KEY_MISSING` / `ACTION_ENCRYPTION_CHANGE` broadcast, and
     * Android can interleave two of those within a few hundred
     * milliseconds during a normal re-pair. To keep the banner from
     * flickering we [debounce] both directions by
     * [BANNER_DEBOUNCE_MS] — the banner is only shown if the peer
     * stays paused for that long, and is only hidden if the peer
     * stays restored for that long.
     */
    @OptIn(FlowPreview::class)
    val bondLossBannerVisible: StateFlow<Boolean> = uiState
        .map { state -> state is ChatUiState.Success && state.isPeerPaused }
        .distinctUntilChanged()
        .debounce(BANNER_DEBOUNCE_MS)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    /**
     * Combined view of decrypted DB messages plus the in-flight
     * optimistic ones, in chronological order. The composable consumes
     * this directly for rendering — the [uiState] flow above is the
     * MVI-shaped projection used for higher-level screen branches
     * (Loading / Error / PeerOffline).
     */
    val messages: StateFlow<List<ChatMessage>> = repository
        .getMessagesByDevice(deviceMac)
        .map(::decryptAll)
        .flowOn(Dispatchers.Default)
        .map { persisted ->
            // Eagerly filter out optimistic items whose text already
            // appears in the persisted set so the UI never shows the
            // duplicate, even for a single frame.
            val persistedTexts = persisted
                .filter { it.isOutgoing }
                .mapTo(HashSet(), ChatMessage::text)
            val dedupedOptimistic = optimistic.value
                .filterNot { it.text in persistedTexts }
            (persisted + dedupedOptimistic)
                .distinctBy(ChatMessage::id)
                .sortedBy(ChatMessage::timestamp)
        }
        .onEach { combined ->
            val persistedTexts = combined
                .filter { it.id >= 0 }
                .mapTo(HashSet(), ChatMessage::text)
            optimistic.update { current ->
                current.filterNot { it.text in persistedTexts }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    init {
        viewModelScope.launch {
            intents.collect { intent ->
                when (intent) {
                    is ChatIntent.SendMessage -> sendMessage(intent.plaintext)
                    ChatIntent.Retry -> Unit
                    ChatIntent.ClearHistory -> repository.deleteMessagesByDevice(deviceMac)
                }
            }
        }
        // Mark every inbound message from this peer as read. The
        // device-list unread badge keys off `MessageEntity.isRead`
        // so opening a chat must clear the badge regardless of which
        // bubble the user actually scrolls past.
        viewModelScope.launch {
            runCatching { repository.markPeerAsRead(deviceMac) }
        }
        // Best-effort RFCOMM auto-connect on chat entry. The transport
        // is null in unit tests and idempotent in production: calling
        // `connect` for a peer with an active session is a no-op, and
        // a failed connect logs and returns rather than throwing —
        // the user can retry by tapping Send (the next attempt will
        // re-trigger the connect on demand).
        val transport = this.transport
        if (transport != null) {
            viewModelScope.launch { transport.connect(deviceMac) }
        }
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: ChatIntent) {
        intents.tryEmit(intent)
    }

    /**
     * Encrypt + persist + transmit [plaintext] through the repository.
     *
     * The optimistic bubble is appended *before* the suspend call so
     * the user sees instant feedback. If the repository call throws,
     * the optimistic entry is rolled back and a `Tampered`-shaped
     * message is left in its place to indicate the local send failure.
     */
    private suspend fun sendMessage(plaintext: String) {
        if (plaintext.isBlank()) return
        val pendingId = -System.nanoTime() // negative so it never collides with Room ids
        val pendingTimestamp = System.currentTimeMillis()
        val pending = ChatMessage(
            id = pendingId,
            text = plaintext,
            isOutgoing = true,
            timestamp = pendingTimestamp,
        )
        optimistic.update { it + pending }
        try {
            repository.sendMessage(deviceMac, plaintext)
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
    }

    /**
     * Map a `MessageEntity` list to decrypted `ChatMessage`s. Pure so
     * unit tests can call it without a coroutine context.
     */
    private fun decryptAll(entities: List<MessageEntity>): List<ChatMessage> =
        entities.map(::toChatMessage)

    private fun toChatMessage(entity: MessageEntity): ChatMessage {
        if (entity.iv.isEmpty()) {
            return ChatMessage(
                id = entity.id,
                text = "",
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
                isCorrupted = true,
            )
        }
        return when (val result = crypto.decrypt(entity.iv, entity.encryptedPayload)) {
            is DecryptionResult.Success -> ChatMessage(
                id = entity.id,
                text = result.plaintext.toString(Charsets.UTF_8),
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
                deliveryStatus = entity.deliveryStatus,
            )
            is DecryptionResult.Tampered -> ChatMessage(
                id = entity.id,
                text = "",
                isOutgoing = entity.isOutgoing,
                timestamp = entity.timestamp,
                isCorrupted = true,
                deliveryStatus = entity.deliveryStatus,
            )
        }
    }

    /**
     * `ChatUiState.Success` historically holds `List<MessageEntity>`
     * so the contract stays binary-compatible with the unit tests
     * planned for step 40. Until that contract is widened to
     * `List<ChatMessage>`, this shim re-encodes a [ChatMessage] back
     * into a stub [MessageEntity] for which the encrypted payload is
     * the UTF-8 plaintext (the screen now reads from [messages]
     * directly anyway).
     */
    private fun toEntityShim(chatMessage: ChatMessage): MessageEntity = MessageEntity(
        id = chatMessage.id.coerceAtLeast(0),
        macAddress = deviceMac,
        encryptedPayload = chatMessage.text.toByteArray(Charsets.UTF_8),
        iv = if (chatMessage.isCorrupted) ByteArray(0) else ByteArray(12),
        timestamp = chatMessage.timestamp,
        isOutgoing = chatMessage.isOutgoing,
    )

    private fun isPaused(): Boolean {
        val impl = repository as? MessageRepositoryImpl ?: return false
        return impl.isPausedFor(deviceMac)
    }

    companion object {
        /** SavedStateHandle key for the active peer MAC address. */
        const val ARG_DEVICE_MAC: String = "deviceMac"

        /**
         * Debounce window applied in both directions to the bond-loss
         * banner visibility flag. 600 ms is a comfortable middle ground:
         * shorter than a typical re-bond cycle so the banner does
         * appear during a real outage, but long enough to swallow the
         * sub-second interleaving that the Android 16 broadcasts emit
         * during a normal re-pair.
         */
        private const val BANNER_DEBOUNCE_MS: Long = 600L

        /**
         * `ViewModelProvider.Factory` that pulls dependencies from the
         * [com.example.bluewave_mobile.BlueWaveApplication.container].
         * Hosts must populate `SavedStateHandle["deviceMac"]` (the
         * Navigation Compose typed route does this automatically).
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                val handle: SavedStateHandle = createSavedStateHandle()
                val mac: String = checkNotNull(handle[ARG_DEVICE_MAC]) {
                    "ChatViewModel requires SavedStateHandle[\"$ARG_DEVICE_MAC\"]"
                }
                ChatViewModel(
                    deviceMac = mac,
                    repository = app.container.messageRepository,
                    crypto = app.container.cryptoManager,
                    transport = app.container.bluetoothSessionManager,
                )
            }
        }
    }
}

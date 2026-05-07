package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.DecryptionResult
import com.example.bluewave_mobile.data.MessageEntity
import com.example.bluewave_mobile.data.MessageRepositoryImpl
import com.example.bluewave_mobile.ui.components.ChatMessage
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.MessageBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-device chat screen.
 *
 * Renders the message history for [deviceMac] in a `reverseLayout` LazyColumn
 * (newest item visually at the bottom) plus an input row at the bottom for
 * sending plaintext.
 *
 * **Decryption.** Step 18 wires a thin presentation layer that pulls the
 * encrypted [MessageEntity] flow out of the repository and decrypts each
 * item through [CryptoManager]. Step 28 will move this logic into a
 * dedicated `ChatViewModel` so the decryption work happens off the main
 * thread and the UI receives `StateFlow<ChatUiState>` instead of
 * `Flow<List<MessageEntity>>`.
 *
 * **Send.** Step 18 launches the suspend `sendMessage` call in a
 * `rememberCoroutineScope` — this is intentionally placeholder behaviour
 * (no optimistic UI, no transmission) so the screen can be exercised
 * end-to-end before the proper VM lands in step 28.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceMac: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember {
        (context.applicationContext as BlueWaveApplication).container
    }
    val scope = rememberCoroutineScope()

    val messagesFlow: StateFlow<List<ChatMessage>> = remember(deviceMac) {
        val crypto = container.cryptoManager
        container.messageRepository
            .getMessagesByDevice(deviceMac)
            .map { entities -> entities.map { entity -> entity.toChatMessage(crypto) } }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = emptyList(),
            )
    }
    val messages by messagesFlow.collectAsState()

    var draft: String by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Polled view of MessageRepositoryImpl.isPausedFor(mac). The repository
    // does not yet expose a Flow for the bond/encryption pause flag
    // (Android 16 ACTION_KEY_MISSING / ACTION_ENCRYPTION_CHANGE) so the UI
    // bridges it via produceState — step 28 will replace this with a
    // proper StateFlow exposed by ChatViewModel.
    val isPaused by produceState(initialValue = false, deviceMac, container) {
        val impl = container.messageRepository as? MessageRepositoryImpl
        if (impl == null) {
            value = false
            return@produceState
        }
        while (true) {
            value = impl.isPausedFor(deviceMac)
            delay(POLL_PAUSE_INTERVAL_MS)
        }
    }
    // Surface bond loss / restore as a Snackbar. The first composition
    // skips the message so users do not see a spurious "Connected" pop
    // every time they open the chat.
    var lastSeenPaused by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isPaused, deviceMac) {
        if (lastSeenPaused != null && lastSeenPaused != isPaused) {
            snackbarHostState.showSnackbar(
                message = if (isPaused) {
                    "Connection lost — waiting for re-bond"
                } else {
                    "Connection restored"
                },
            )
        }
        lastSeenPaused = isPaused
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chat",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = deviceMac,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (messages.isEmpty()) {
                    EmptyStateView(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No messages yet",
                        message = "Start the conversation by sending a message below.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.Top,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = messages.asReversed(),
                            key = ChatMessage::id,
                        ) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            ChatInputRow(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    val outgoing = draft
                    if (outgoing.isNotBlank()) {
                        draft = ""
                        scope.launch {
                            container.messageRepository.sendMessage(deviceMac, outgoing)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatInputRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Message") },
            singleLine = false,
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Message input" },
        )
        IconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { contentDescription = "Send message" },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Maps a raw [MessageEntity] (encrypted payload + IV) to the UI-facing
 * [ChatMessage] model.
 *
 * A successful GCM authentication decodes the bytes as UTF-8 plaintext;
 * a tampered payload — or a payload too short to contain a valid IV —
 * produces a corrupted bubble. The mapper is intentionally pure so it
 * can be relocated to `ChatViewModel` in step 28 without behavioural
 * change.
 */
/**
 * Polling cadence (ms) for [MessageRepositoryImpl.isPausedFor] until the
 * repository exposes a Flow for the Android 16 bond / encryption pause
 * flag. The 500 ms interval keeps the snackbar latency low while
 * remaining negligible compared with a Bluetooth re-bond cycle (which
 * takes seconds).
 */
private const val POLL_PAUSE_INTERVAL_MS: Long = 500L

private fun MessageEntity.toChatMessage(crypto: CryptoManager): ChatMessage {
    if (iv.isEmpty()) {
        return ChatMessage(
            id = id,
            text = "",
            isOutgoing = isOutgoing,
            timestamp = timestamp,
            isCorrupted = true,
        )
    }
    return when (val result = crypto.decrypt(iv, encryptedPayload)) {
        is DecryptionResult.Success -> ChatMessage(
            id = id,
            text = result.plaintext.toString(Charsets.UTF_8),
            isOutgoing = isOutgoing,
            timestamp = timestamp,
        )
        is DecryptionResult.Tampered -> ChatMessage(
            id = id,
            text = "",
            isOutgoing = isOutgoing,
            timestamp = timestamp,
            isCorrupted = true,
        )
    }
}

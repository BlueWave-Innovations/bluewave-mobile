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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.MessageBubble
import com.example.bluewave_mobile.ui.components.SendButton
import com.example.bluewave_mobile.ui.intent.ChatIntent
import com.example.bluewave_mobile.ui.state.ChatMessage
import com.example.bluewave_mobile.ui.state.ChatUiState
import com.example.bluewave_mobile.ui.viewmodel.ChatViewModel

/**
 * Per-device chat screen.
 *
 * Renders the message history for [deviceMac] in a `reverseLayout` LazyColumn
 * (newest item visually at the bottom) plus an input row at the bottom for
 * sending plaintext.
 *
 * Step 28 hoisted decryption + send orchestration into [ChatViewModel].
 * The composable now only:
 *
 *  * subscribes to [ChatViewModel.messages] (already-decrypted) and
 *    [ChatViewModel.uiState] (MVI screen state);
 *  * forwards user actions through [ChatIntent]; and
 *  * surfaces bond loss / restore as a snackbar by observing
 *    [ChatUiState.Success.isPeerPaused].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceMac: String,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory,
        key = deviceMac,
    ),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var draft: String by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val isPaused = (uiState as? ChatUiState.Success)?.isPeerPaused == true
    var lastSeenPaused: Boolean? by remember { mutableStateOf<Boolean?>(null) }
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
                ChatBody(
                    state = uiState,
                    messages = messages,
                    listState = listState,
                )
            }

            ChatInputRow(
                draft = draft,
                enabled = !isPaused,
                onDraftChange = { draft = it },
                onSend = {
                    val outgoing = draft
                    if (outgoing.isNotBlank()) {
                        draft = ""
                        viewModel.handleIntent(ChatIntent.SendMessage(outgoing))
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatBody(
    state: ChatUiState,
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    when {
        state is ChatUiState.Error -> {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Couldn't load history",
                message = state.message,
                modifier = modifier.fillMaxSize(),
            )
        }
        messages.isEmpty() -> {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "No messages yet",
                message = "Start the conversation by sending a message below.",
                modifier = modifier.fillMaxSize(),
            )
        }
        else -> {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
                modifier = modifier.fillMaxSize(),
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
}

@Composable
private fun ChatInputRow(
    draft: String,
    enabled: Boolean,
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
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Message input" },
        )
        SendButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

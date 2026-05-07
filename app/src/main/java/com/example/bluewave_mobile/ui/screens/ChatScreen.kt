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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.E2EEState
import com.example.bluewave_mobile.ui.components.BondLossBanner
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
    val bannerVisible by viewModel.bondLossBannerVisible.collectAsStateWithLifecycle()

    var draft: String by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // `derivedStateOf` collapses every scroll-position change inside
    // the LazyColumn into a single boolean. Without it, every pixel of
    // scrolling would invalidate any composable that observed the raw
    // `firstVisibleItemIndex`. With it, the jump-to-bottom FAB only
    // recomposes when the threshold is crossed (~once per page).
    val showJumpToBottom: Boolean by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > JUMP_TO_BOTTOM_THRESHOLD }
    }

    val isPaused = (uiState as? ChatUiState.Success)?.isPeerPaused == true
    val e2eeState: E2EEState = (uiState as? ChatUiState.Success)?.e2eeState ?: E2EEState.PENDING
    var lastSeenBanner: Boolean? by remember { mutableStateOf<Boolean?>(null) }
    val connectionRestoredMessage = stringResource(id = R.string.chat_connection_restored)
    LaunchedEffect(bannerVisible, deviceMac) {
        if (lastSeenBanner != null && lastSeenBanner != bannerVisible && !bannerVisible) {
            snackbarHostState.showSnackbar(message = connectionRestoredMessage)
        }
        lastSeenBanner = bannerVisible
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val chatWithCd = stringResource(id = R.string.chat_with_cd, deviceMac)
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            heading()
                            contentDescription = chatWithCd
                        },
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(id = R.string.chat_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = deviceMac,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        E2EEIndicator(state = e2eeState)
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
            BondLossBanner(visible = bannerVisible)
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
                if (showJumpToBottom) {
                    val jumpToBottomCd = stringResource(id = R.string.chat_jump_to_bottom_cd)
                    FloatingActionButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(40.dp)
                            .semantics { contentDescription = jumpToBottomCd },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                }
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

/**
 * Number of items the user has to scroll *up* (in `reverseLayout`
 * coordinates the bottom item is index 0) before the jump-to-bottom
 * FAB appears. 5 keeps the FAB out of the way for typical
 * conversations and only surfaces it when the user is meaningfully
 * lost in history.
 */
private const val JUMP_TO_BOTTOM_THRESHOLD: Int = 5

@Composable
private fun ChatBody(
    state: ChatUiState,
    messages: List<ChatMessage>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    when {
        state is ChatUiState.Error -> {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(id = R.string.chat_history_error_title),
                message = state.message,
                modifier = modifier.fillMaxSize(),
            )
        }
        messages.isEmpty() -> {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(id = R.string.chat_empty_title),
                message = stringResource(id = R.string.chat_empty_message),
                modifier = modifier.fillMaxSize(),
            )
        }
        else -> {
            // SelectionContainer turns the LazyColumn into a single
            // selectable region so a user with a hardware keyboard,
            // mouse, or trackpad (Chromebook / Android-on-laptop) can
            // drag-select message text and copy it. The container
            // gracefully degrades on touch devices: long-press
            // selection and the standard floating action menu still
            // work as on any other Compose Text.
            SelectionContainer(modifier = modifier.fillMaxSize()) {
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
    }
}

/**
 * Tiny lock indicator surfaced in the chat top-bar. The icon
 * (and its `contentDescription`) tracks the current
 * [E2EEState]:
 *
 *  * [E2EEState.SECURE]  — closed lock, "End-to-end encrypted";
 *  * [E2EEState.PENDING] — sync arrows, "Establishing secure session";
 *  * [E2EEState.FAILED]  — open lock, "Authentication failed".
 *
 * Kept intentionally minimal — anything fancier (animation,
 * banner) would compete with the bond-loss banner that already
 * lives at the top of the screen.
 */
@Composable
private fun E2EEIndicator(
    state: E2EEState,
    modifier: Modifier = Modifier,
) {
    val (icon, descriptionRes) = when (state) {
        E2EEState.SECURE -> Icons.Filled.Lock to R.string.chat_security_encrypted_cd
        E2EEState.PENDING -> Icons.Filled.Sync to R.string.chat_security_pending_cd
        E2EEState.FAILED -> Icons.Filled.LockOpen to R.string.chat_security_failed_cd
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(id = descriptionRes),
        modifier = modifier
            .padding(start = 8.dp)
            .size(20.dp),
    )
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
        val inputCd = stringResource(id = R.string.chat_input_cd)
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(id = R.string.chat_input_placeholder)) },
            singleLine = false,
            maxLines = 4,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = inputCd },
        )
        SendButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

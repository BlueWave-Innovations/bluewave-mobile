package com.example.bluewave_mobile.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.E2EEState
import com.example.bluewave_mobile.ui.components.BondLossBanner
import com.example.bluewave_mobile.ui.components.ChatInputRow
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.MessageBubble
import com.example.bluewave_mobile.ui.intent.ChatIntent
import com.example.bluewave_mobile.ui.state.ChatMessage
import com.example.bluewave_mobile.ui.state.ChatUiState
import com.example.bluewave_mobile.ui.viewmodel.ChatViewModel
import com.example.bluewave_mobile.ui.viewmodel.ConnectionQuality
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Per-device chat screen.
 *
 * Renders the message history for [deviceMac] in a `reverseLayout` LazyColumn
 * (newest item visually at the bottom) plus an input row at the bottom for
 * sending plaintext.
 *
 * Step 28 hoisted decryption + send orchestration into [ChatViewModel].
 * The top bar shows the peer's avatar, display name + handle, and the
 * E2EE lock indicator. The online/offline badge was removed to keep the
 * UX focused on message delivery rather than presence guesses.
 *
 * The composable now only:
 *
 *  * subscribes to [ChatViewModel.messages] (already-decrypted),
 *    [ChatViewModel.uiState] (MVI screen state) and
 *    [ChatViewModel.peerProfile] (cached `PROFILE_METADATA`);
 *  * forwards user actions through [ChatIntent]; and
 *  * surfaces bond loss / restore as a snackbar by observing
 *    [ChatUiState.Success.isPeerPaused].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceMac: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory,
        key = deviceMac,
    ),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bannerVisible by viewModel.bondLossBannerVisible.collectAsStateWithLifecycle()
    val peerProfile by viewModel.peerProfile.collectAsStateWithLifecycle()
    val connQuality by viewModel.connectionQuality.collectAsStateWithLifecycle()

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
            val displayName: String = peerProfile?.displayName?.takeUnless(String::isBlank)
                ?: stringResource(id = R.string.chat_title)
            val handle: String? = peerProfile?.handle?.takeUnless(String::isBlank)
            val chatWithCd = stringResource(id = R.string.chat_with_cd, displayName)
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.common_back),
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            heading()
                            contentDescription = chatWithCd
                        },
                    ) {
                        ChatAvatar(
                            displayName = displayName,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            ConnectionQualityRow(quality = connQuality)
                            if (handle != null) {
                                Text(
                                    text = handle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
            val todayLabel = stringResource(id = R.string.chat_date_today)
            val yesterdayLabel = stringResource(id = R.string.chat_date_yesterday)
            val items: List<ChatListItem> = remember(messages, todayLabel, yesterdayLabel) {
                buildChatListItems(
                    messages = messages,
                    todayLabel = todayLabel,
                    yesterdayLabel = yesterdayLabel,
                )
            }
            SelectionContainer(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        count = items.size,
                        key = { index -> items[index].key },
                    ) { index ->
                        when (val item = items[index]) {
                            is ChatListItem.Bubble -> MessageBubble(message = item.message)
                            is ChatListItem.DateHeader -> DateSeparator(label = item.label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sealed model of every renderable row in the chat list. Splitting
 * messages and date headers into a single typed list lets the
 * `LazyColumn` use stable keys without each header racing against an
 * adjacent message id.
 */
internal sealed interface ChatListItem {
    val key: String

    data class Bubble(val message: ChatMessage) : ChatListItem {
        override val key: String = "msg:${message.id}"
    }

    data class DateHeader(
        val label: String,
        private val dayKey: Long,
    ) : ChatListItem {
        override val key: String = "date:$dayKey"
    }
}

/**
 * Builds the `LazyColumn`-friendly list of `ChatListItem`s consumed by
 * the chat screen.
 *
 * Headers are inserted **above** the first message of each calendar
 * day. The function returns the list newest-first so the chat
 * composable can feed it straight into the `reverseLayout` LazyColumn
 * without an extra `asReversed()` pass.
 *
 * The function is `internal` so the unit-test target can inspect the
 * boundary cases without spinning up the Android renderer.
 *
 * @param messages messages in any order; sorted ascending by
 *                 [ChatMessage.timestamp] inside the function.
 * @param locale the locale used to render the calendar-day labels.
 *               Defaulted to the system locale; tests pass a fixed
 *               locale for deterministic output.
 * @param now wall-clock instant used to compute "Today / Yesterday"
 *            labels — defaulted to [System.currentTimeMillis] so the
 *            production caller doesn't need to thread it through.
 * @param todayLabel localised "Today" label injected by the caller.
 *                   `null` falls back to the absolute date so the
 *                   helper stays usable from `androidTest`-only paths
 *                   that don't have a Compose context.
 * @param yesterdayLabel mirror of [todayLabel] for the previous day.
 */
internal fun buildChatListItems(
    messages: List<ChatMessage>,
    locale: Locale = Locale.getDefault(),
    now: Long = System.currentTimeMillis(),
    todayLabel: String? = null,
    yesterdayLabel: String? = null,
): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()
    val sorted: List<ChatMessage> = messages.sortedBy(ChatMessage::timestamp)
    val out: MutableList<ChatListItem> = ArrayList(sorted.size + 4)
    var lastDay: Long = Long.MIN_VALUE
    for (message in sorted) {
        val day = startOfDay(message.timestamp)
        if (day != lastDay) {
            val label = formatDateSeparator(
                timestamp = message.timestamp,
                now = now,
                locale = locale,
                todayLabel = todayLabel,
                yesterdayLabel = yesterdayLabel,
            )
            out += ChatListItem.DateHeader(label = label, dayKey = day)
            lastDay = day
        }
        out += ChatListItem.Bubble(message = message)
    }
    return out.asReversed()
}

private fun startOfDay(timestamp: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatDateSeparator(
    timestamp: Long,
    now: Long,
    locale: Locale,
    todayLabel: String?,
    yesterdayLabel: String?,
): String {
    val today = startOfDay(now)
    val day = startOfDay(timestamp)
    val deltaDays = ((today - day) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        deltaDays == 0 && todayLabel != null -> todayLabel
        deltaDays == 1 && yesterdayLabel != null -> yesterdayLabel
        else -> {
            // `MEDIUM` produces "May 7", "7 мая", etc. depending on
            // locale and is what most messengers use as the day
            // separator label.
            val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            fmt.format(Date(timestamp))
        }
    }
}

@Composable
private fun DateSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 40dp circular avatar placed at the leading edge of the chat top bar.
 *
 * Renders the first non-blank character of [displayName] uppercased on
 * a tinted circle (the same styling as the contact-list avatars) so
 * the chat screen feels like a continuation of the row the user just
 * tapped.
 */
@Composable
private fun ChatAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
) {
    val initial: String = displayName
        .trim()
        .firstOrNull()
        ?.uppercase()
        ?: "?"
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
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

/**
 * Compact row showing the peer's connection status: a coloured dot
 * (green = online, grey = offline), the quality label derived from
 * the heartbeat RTT, and the raw ping value when available.
 */
@Composable
private fun ConnectionQualityRow(
    quality: ConnectionQuality,
    modifier: Modifier = Modifier,
) {
    val dotColor = if (quality.isOnline) {
        com.example.bluewave_mobile.ui.theme.SuccessGreen
    } else {
        MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = quality.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (quality.isOnline) {
                com.example.bluewave_mobile.ui.theme.BrandBlue
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        if (quality.pingMs != null) {
            Spacer(modifier = Modifier.width(6.dp))
            SignalBars(pingMs = quality.pingMs)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${quality.pingMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Four tiny vertical bars whose fill colour reflects the heartbeat
 * ping latency — a familiar "signal strength" metaphor.
 *
 *  * <100 ms → 4 green bars
 *  * <300 ms → 3 green bars
 *  * <600 ms → 2 yellow bars
 *  * ≥600 ms → 1 red bar
 */
@Composable
private fun SignalBars(
    pingMs: Long,
    modifier: Modifier = Modifier,
) {
    val filledBars: Int = when {
        pingMs < 100L -> 4
        pingMs < 300L -> 3
        pingMs < 600L -> 2
        else -> 1
    }
    val activeColor: Color = when {
        pingMs < 300L -> com.example.bluewave_mobile.ui.theme.SuccessGreen
        pingMs < 600L -> Color(0xFFFFA726) // orange / yellow
        else -> Color(0xFFEF5350) // red
    }
    val inactiveColor: Color = MaterialTheme.colorScheme.outlineVariant
    val totalBars = 4
    Canvas(modifier = modifier.size(width = 16.dp, height = 12.dp)) {
        val barWidth = size.width / (totalBars * 2f - 1f)
        val gap = barWidth
        for (i in 0 until totalBars) {
            val barHeight = size.height * (i + 1) / totalBars
            val x = i * (barWidth + gap)
            val y = size.height - barHeight
            drawRect(
                color = if (i < filledBars) activeColor else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

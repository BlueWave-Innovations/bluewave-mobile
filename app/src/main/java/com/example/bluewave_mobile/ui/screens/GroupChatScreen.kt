package com.example.bluewave_mobile.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.bluewave_mobile.ui.components.ChatInputRow
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.GroupMessageBubble
import com.example.bluewave_mobile.ui.intent.GroupChatIntent
import com.example.bluewave_mobile.ui.state.GroupChatMessage
import com.example.bluewave_mobile.ui.state.GroupChatUiState
import com.example.bluewave_mobile.ui.viewmodel.GroupChatViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Multi-peer group chat screen.
 *
 * Mirrors the redesigned [ChatScreen]:
 *  * 40 dp avatar (group icon on a tinted circle, no online dot —
 *    "online" is per-member rather than per-group);
 *  * group name + "%d members" stacked to the right of the avatar.
 *
 * The body is a [SelectionContainer] wrapping a `reverseLayout`
 * [LazyColumn] of [GroupMessageBubble]s separated by date headers,
 * just like [ChatScreen]. The first bubble in any contiguous run
 * of messages from the same sender shows the sender's display name
 * label; subsequent bubbles in that run hide the label so the
 * stream reads as Telegram-style grouped messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: GroupChatViewModel = viewModel(
        factory = GroupChatViewModel.Factory,
        key = groupId,
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var draft: String by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            val displayName: String = (uiState as? GroupChatUiState.Success)
                ?.group
                ?.name
                ?.takeUnless(String::isBlank)
                ?: stringResource(id = R.string.group_chat_title)
            val memberCount: Int = (uiState as? GroupChatUiState.Success)?.members?.size ?: 0
            val memberLabel: String = stringResource(
                id = R.string.group_chat_member_count,
                memberCount,
            )
            val groupCd = stringResource(id = R.string.group_chat_with_cd, displayName)
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            heading()
                            contentDescription = groupCd
                        },
                    ) {
                        GroupAvatar()
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
                            Text(
                                text = memberLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_back),
                        )
                    }
                },
            )
        },
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
                GroupChatBody(
                    state = uiState,
                    listState = listState,
                )
            }
            ChatInputRow(
                draft = draft,
                enabled = true,
                onDraftChange = { draft = it },
                onSend = {
                    val outgoing = draft
                    if (outgoing.isNotBlank()) {
                        draft = ""
                        viewModel.handleIntent(GroupChatIntent.SendMessage(outgoing))
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupChatBody(
    state: GroupChatUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is GroupChatUiState.Loading -> Unit
        is GroupChatUiState.Error -> {
            EmptyStateView(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = stringResource(id = R.string.chat_history_error_title),
                message = state.message,
                modifier = modifier.fillMaxSize(),
            )
        }
        is GroupChatUiState.Success -> {
            if (state.messages.isEmpty()) {
                EmptyStateView(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = stringResource(id = R.string.chat_empty_title),
                    message = stringResource(id = R.string.chat_empty_message),
                    modifier = modifier.fillMaxSize(),
                )
            } else {
                val todayLabel = stringResource(id = R.string.chat_date_today)
                val yesterdayLabel = stringResource(id = R.string.chat_date_yesterday)
                val items: List<GroupListItem> = remember(state.messages, todayLabel, yesterdayLabel) {
                    buildGroupListItems(
                        messages = state.messages,
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
                                is GroupListItem.Bubble -> GroupMessageBubble(
                                    message = item.message,
                                    showSender = item.showSender,
                                )
                                is GroupListItem.DateHeader -> GroupDateSeparator(label = item.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sealed model of every renderable row in the group chat list.
 * Mirrors `ChatListItem` but carries the per-bubble `showSender`
 * flag so the screen can render Telegram-style grouped runs of
 * messages from the same author with one name label up top.
 */
internal sealed interface GroupListItem {
    val key: String

    data class Bubble(
        val message: GroupChatMessage,
        val showSender: Boolean,
    ) : GroupListItem {
        override val key: String = "msg:${message.id}"
    }

    data class DateHeader(
        val label: String,
        private val dayKey: Long,
    ) : GroupListItem {
        override val key: String = "date:$dayKey"
    }
}

/**
 * Builds the LazyColumn-friendly list of [GroupListItem]s consumed
 * by the group chat screen. The function is `internal` so the
 * unit-test target can inspect the sender-label / date-header
 * logic without spinning up the Android renderer.
 */
internal fun buildGroupListItems(
    messages: List<GroupChatMessage>,
    locale: Locale = Locale.getDefault(),
    now: Long = System.currentTimeMillis(),
    todayLabel: String? = null,
    yesterdayLabel: String? = null,
): List<GroupListItem> {
    if (messages.isEmpty()) return emptyList()
    val sorted: List<GroupChatMessage> = messages.sortedBy(GroupChatMessage::timestamp)
    val out: MutableList<GroupListItem> = ArrayList(sorted.size + 4)
    var lastDay: Long = Long.MIN_VALUE
    var lastSender: String? = null
    for (message in sorted) {
        val day = startOfGroupDay(message.timestamp)
        if (day != lastDay) {
            val label = formatGroupDateSeparator(
                timestamp = message.timestamp,
                now = now,
                locale = locale,
                todayLabel = todayLabel,
                yesterdayLabel = yesterdayLabel,
            )
            out += GroupListItem.DateHeader(label = label, dayKey = day)
            lastDay = day
            lastSender = null
        }
        val showSender = !message.isOutgoing &&
            message.senderName.isNotBlank() &&
            lastSender != message.senderMac.uppercase()
        out += GroupListItem.Bubble(message = message, showSender = showSender)
        lastSender = message.senderMac.uppercase()
    }
    return out.asReversed()
}

private fun startOfGroupDay(timestamp: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestamp
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private fun formatGroupDateSeparator(
    timestamp: Long,
    now: Long,
    locale: Locale,
    todayLabel: String?,
    yesterdayLabel: String?,
): String {
    val today = startOfGroupDay(now)
    val day = startOfGroupDay(timestamp)
    val deltaDays = ((today - day) / DateUtils.DAY_IN_MILLIS).toInt()
    return when {
        deltaDays == 0 && todayLabel != null -> todayLabel
        deltaDays == 1 && yesterdayLabel != null -> yesterdayLabel
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(timestamp))
    }
}

/**
 * 40 dp circular avatar for the group chat top bar. Renders the
 * Material "Group" icon centered on the same tinted surface as the
 * one-on-one chat avatar so the visual rhythm matches.
 */
@Composable
private fun GroupAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun GroupDateSeparator(
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

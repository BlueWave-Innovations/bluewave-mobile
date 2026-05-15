package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.state.GroupChatMessage
import java.text.DateFormat
import java.util.Date

/**
 * Renders a single chat bubble inside a multi-peer group chat,
 * aligned to the start (incoming) or end (outgoing) of the row.
 *
 * Mirrors [MessageBubble] visually but adds a sender-name label
 * above the message text for inbound messages so the user can tell
 * "Алекс" from "Маша" in the running stream. The label is hidden
 * for outgoing messages and for consecutive inbound messages from
 * the same sender (controlled by [showSender]) so vertically-stacked
 * "Алекс: foo / Алекс: bar / Алекс: baz" reads as Telegram-like
 * grouped bubbles instead of three separate name pings.
 *
 * @param showSender Whether to render the sender name above the
 *                   bubble. The screen sets this to `true` for the
 *                   first bubble in any contiguous run of messages
 *                   from the same sender, and `false` for the rest.
 */
@Composable
fun GroupMessageBubble(
    message: GroupChatMessage,
    showSender: Boolean,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        message.isCorrupted -> MaterialTheme.colorScheme.errorContainer
        message.isOutgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        message.isCorrupted -> MaterialTheme.colorScheme.onErrorContainer
        message.isOutgoing -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val timeFormatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val formattedTime = remember(message.timestamp) {
        timeFormatter.format(Date(message.timestamp))
    }
    val descriptionTemplate = when {
        message.isOutgoing && message.isCorrupted ->
            stringResource(R.string.chat_message_sent_corrupted_cd, formattedTime)
        !message.isOutgoing && message.isCorrupted ->
            stringResource(R.string.chat_message_received_corrupted_cd, formattedTime)
        message.isOutgoing ->
            stringResource(R.string.chat_message_sent_at_cd, formattedTime, message.text)
        else ->
            stringResource(
                R.string.group_chat_message_received_at_cd,
                message.senderName.ifBlank { message.senderMac },
                formattedTime,
                message.text,
            )
    }
    val description = remember(message, descriptionTemplate) { descriptionTemplate }

    val securityIcon: ImageVector = if (message.isCorrupted) {
        Icons.Outlined.LockOpen
    } else {
        Icons.Filled.Lock
    }
    val securityDescription = if (message.isCorrupted) {
        stringResource(R.string.chat_security_failed_cd)
    } else {
        stringResource(R.string.chat_security_encrypted_cd)
    }
    val corruptedLabel = stringResource(R.string.chat_corrupted_label)
    val corruptedMessage = stringResource(R.string.chat_corrupted_message)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        val bubbleModifier = Modifier
            .widthIn(max = 320.dp)
            .background(containerColor, RoundedCornerShape(16.dp))
            .let { base ->
                if (message.isCorrupted) {
                    base.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(16.dp),
                    )
                } else {
                    base
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }

        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = bubbleModifier,
        ) {
            if (showSender && !message.isOutgoing && message.senderName.isNotBlank()) {
                Text(
                    text = message.senderName,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (message.isCorrupted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = corruptedLabel,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = corruptedMessage,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = message.text,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = securityIcon,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(12.dp)
                        .semantics { contentDescription = securityDescription },
                )
                Text(
                    text = formattedTime,
                    color = contentColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

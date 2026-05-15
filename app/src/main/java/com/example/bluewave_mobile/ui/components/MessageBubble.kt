package com.example.bluewave_mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.MessageEntity
import com.example.bluewave_mobile.ui.state.ChatMessage
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.theme.BrandBlueLight
import java.text.DateFormat
import java.util.Date
import java.io.File

/**
 * Renders a single chat bubble aligned to the start (incoming) or end
 * (outgoing) of the row.
 *
 * Bubble styling:
 *  * outgoing → brand blue gradient, white text, tail in bottom-end corner.
 *  * incoming → surface-variant fill, on-surface text, tail in bottom-start.
 *  * corrupted → error container with a 1dp outline.
 *
 * The composable is stateless and pure — all timing / decryption
 * happens upstream in the ChatScreen mapper.
 */
private fun openFile(context: android.content.Context, file: File, mimeType: String) {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File not found", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app can open this file", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun AttachmentContent(
    message: ChatMessage,
    contentColor: Color,
    outgoing: Boolean,
) {
    val context = LocalContext.current
    val mimeType = message.attachmentMimeType
    when {
        mimeType.startsWith("image/") -> {
            val file = File(message.attachmentPath)
            AsyncImage(
                model = file,
                contentDescription = message.attachmentName,
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(maxWidth = 280.dp, maxHeight = 240.dp)
                    .clickable {
                        openFile(context, file, mimeType)
                    },
            )
        }
        mimeType.startsWith("video/") -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    openFile(context, File(message.attachmentPath), mimeType)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = message.attachmentName,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = formatFileSize(message.attachmentSize),
                        color = contentColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    openFile(context, File(message.attachmentPath), mimeType)
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = message.attachmentName,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = formatFileSize(message.attachmentSize),
                        color = contentColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String = when {
    size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
    size >= 1_024 -> "%.0f KB".format(size / 1_024.0)
    else -> "$size B"
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onCancelSend: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val outgoingBrush: Brush = remember {
        Brush.linearGradient(colors = listOf(BrandBlue, BrandBlueLight))
    }
    val errorBrush: Brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.errorContainer,
        ),
    )
    val incomingBrush: Brush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
    val containerBrush: Brush = when {
        message.isCorrupted -> errorBrush
        message.isOutgoing -> outgoingBrush
        else -> incomingBrush
    }
    val contentColor: Color = when {
        message.isCorrupted -> MaterialTheme.colorScheme.onErrorContainer
        message.isOutgoing -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    // Subtle scale-in when a fresh bubble lands. The animation runs
    // once per bubble — the key is the message id, so re-composing
    // an already-mounted bubble doesn't re-animate it.
    val initialScale: Float by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "bubble-in",
    )

    val timeFormatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val formattedTime = remember(message.timestamp) {
        timeFormatter.format(Date(message.timestamp))
    }
    val descriptionTemplate = when {
        message.isOutgoing && message.isCorrupted ->
            stringResource(R.string.chat_message_sent_corrupted_cd, formattedTime)
        !message.isOutgoing && message.isCorrupted ->
            stringResource(R.string.chat_message_received_corrupted_cd, formattedTime)
        message.attachmentPath.isNotBlank() -> {
            val label = message.attachmentName.ifBlank { "attachment" }
            if (message.isOutgoing) {
                stringResource(R.string.chat_message_sent_at_cd, formattedTime, label)
            } else {
                stringResource(R.string.chat_message_received_at_cd, formattedTime, label)
            }
        }
        message.isOutgoing ->
            stringResource(R.string.chat_message_sent_at_cd, formattedTime, message.text)
        else ->
            stringResource(R.string.chat_message_received_at_cd, formattedTime, message.text)
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

    val bubbleShape: Shape = if (message.isOutgoing) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .scale(initialScale),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        val bubbleModifier = Modifier
            .widthIn(max = 320.dp)
            .background(brush = containerBrush, shape = bubbleShape)
            .let { base ->
                if (message.isCorrupted) {
                    base.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error,
                        shape = bubbleShape,
                    )
                } else {
                    base
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            // mergeDescendants collapses every nested Text / Icon
            // (timestamp, security indicator, warning row) into a
            // single TalkBack node. Without it, screen readers narrate
            // the bubble as half a dozen separate elements, which is
            // particularly noisy on the corrupted-message variant.
            .semantics(mergeDescendants = true) { contentDescription = description }

        Column(
            horizontalAlignment = Alignment.Start,
            modifier = bubbleModifier,
        ) {
            when {
                message.isCorrupted -> {
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
                }
                message.attachmentPath.isNotBlank() -> {
                    AttachmentContent(
                        message = message,
                        contentColor = contentColor,
                        outgoing = message.isOutgoing,
                    )
                    if (message.isOutgoing && message.transferStatus == com.example.bluewave_mobile.data.MessageEntity.TRANSFER_UPLOADING) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = contentColor.copy(alpha = 0.7f),
                            trackColor = contentColor.copy(alpha = 0.2f),
                        )
                        if (onCancelSend != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onCancelSend(message.id) },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = "Cancel",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }
                    }
                    if (message.isOutgoing && message.transferStatus == com.example.bluewave_mobile.data.MessageEntity.TRANSFER_FAILED) {
                        Text(
                            text = "Send failed",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                else -> {
                    Text(
                        text = message.text,
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = securityIcon,
                    // The Row already carries the security label via
                    // semantics; the icon is decorative for TalkBack.
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
                if (message.isOutgoing && !message.isCorrupted) {
                    val statusText = if (message.deliveryStatus >= MessageEntity.STATUS_DELIVERED) "✓✓" else "✓"
                    Text(
                        text = statusText,
                        color = contentColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

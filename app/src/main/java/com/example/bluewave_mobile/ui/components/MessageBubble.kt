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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.ui.state.ChatMessage
import java.text.DateFormat
import java.util.Date

/**
 * Renders a single chat bubble aligned to the start (incoming) or end
 * (outgoing) of the row.
 *
 * Material 3 colour roles are intentional:
 *  * outgoing → `primaryContainer` / `onPrimaryContainer`;
 *  * incoming → `surfaceVariant` / `onSurfaceVariant`.
 *
 * The composable is stateless and pure — all timing / decryption happens
 * upstream in the ChatScreen mapper. Step 24 will add a security
 * indicator next to the timestamp; step 26 will switch corrupted
 * bubbles to `errorContainer`.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
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
    val description = remember(message) {
        val direction = if (message.isOutgoing) "Sent" else "Received"
        if (message.isCorrupted) {
            "$direction at $formattedTime, message corrupted"
        } else {
            "$direction at $formattedTime: ${message.text}"
        }
    }

    val securityIcon: ImageVector = if (message.isCorrupted) {
        Icons.Outlined.LockOpen
    } else {
        Icons.Filled.Lock
    }
    val securityDescription = if (message.isCorrupted) {
        "Authentication failed"
    } else {
        "End-to-end encrypted"
    }

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
            // mergeDescendants collapses every nested Text / Icon
            // (timestamp, security indicator, warning row) into a
            // single TalkBack node. Without it, screen readers narrate
            // the bubble as half a dozen separate elements, which is
            // particularly noisy on the corrupted-message variant.
            .semantics(mergeDescendants = true) { contentDescription = description }

        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = bubbleModifier,
        ) {
            if (message.isCorrupted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Authenticity check failed",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = "This message was tampered with in transit and cannot be displayed.",
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
            }
        }
    }
}

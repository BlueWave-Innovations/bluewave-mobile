package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * UI model surfaced by `ChatScreen` for a single message line.
 *
 * The data layer ([com.example.bluewave_mobile.data.MessageEntity]) holds the
 * encrypted payload and IV; the chat presentation layer is responsible for
 * decrypting via [com.example.bluewave_mobile.crypto.CryptoManager.decrypt]
 * and mapping the result to one of the cases below. Keeping this UI model
 * decoupled from the database entity lets the LazyColumn diff stable items
 * without leaking ByteArray identity into the recomposition graph.
 *
 * @property id Stable Room row id used as the LazyColumn key.
 * @property text Decrypted plaintext to render. Empty when [isCorrupted] is true.
 * @property isOutgoing True if this device authored the message.
 * @property timestamp Unix epoch ms; formatted at render time.
 * @property isCorrupted True if the GCM tag rejected the ciphertext — step 26
 *                       refines the rendering of this case via
 *                       [androidx.compose.material3.ColorScheme.errorContainer].
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val isCorrupted: Boolean = false,
)

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(containerColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = description },
        ) {
            Text(
                text = if (message.isCorrupted) "[Message could not be decrypted]" else message.text,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = formattedTime,
                color = contentColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

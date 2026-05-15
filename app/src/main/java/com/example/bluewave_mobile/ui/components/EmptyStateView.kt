package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import com.example.bluewave_mobile.ui.preview.PreviewFontScales
import com.example.bluewave_mobile.ui.preview.PreviewLightDark
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme

/**
 * Reusable, edge-case-only placeholder shown when a screen has zero
 * data to render.
 *
 * Three call sites at the time of writing:
 *
 *  * [com.example.bluewave_mobile.ui.screens.DeviceListScreen] when
 *    discovery returned no peers (radio is on but nobody is in range).
 *  * [com.example.bluewave_mobile.ui.screens.ChatScreen] when the
 *    Room flow for a peer emits an empty list (fresh contact).
 *  * Permission / Bluetooth-disabled gates rendered by the same
 *    composable for consistency.
 *
 * The component is intentionally **stateless and side-effect-free**.
 * It accepts a single optional [onAction] lambda so consumers can wire
 * any retry / open-settings / start-scan CTA they want without
 * branching on a screen-specific enum here.
 *
 * Accessibility: the icon is given an explicit `contentDescription`
 * derived from [title] so TalkBack announces meaningful text instead
 * of "image". The whole layout is exposed as a single semantics node
 * via `Modifier.semantics(mergeDescendants = true)`, which means
 * screen readers focus the entire empty-state as one element instead
 * of jumping between icon / title / message.
 *
 * @param icon Image vector to display above the title — pick one that
 *             communicates the *cause* of the empty state.
 * @param title Short headline (1 line, 16sp).
 * @param message Supporting copy (max 2 lines, 14sp).
 * @param actionLabel Optional CTA text. Pass `null` to hide the button.
 * @param onAction Invoked when the CTA button is tapped. Ignored when
 *                 [actionLabel] is `null`.
 * @param modifier Layout / positioning modifier supplied by the host.
 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $message"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            // Intentionally null — the parent semantics node carries
            // the human-readable label, leaving this Icon as a
            // decorative element for TalkBack.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

@PreviewLightDark
@PreviewFontScales
@Composable
private fun EmptyStateViewPreview() {
    BlueWaveTheme {
        EmptyStateView(
            icon = Icons.Filled.BluetoothDisabled,
            title = "No devices nearby",
            message = "Move closer to a paired phone or tap the button below to start a fresh scan.",
            actionLabel = "Scan again",
            onAction = {}
        )
    }
}

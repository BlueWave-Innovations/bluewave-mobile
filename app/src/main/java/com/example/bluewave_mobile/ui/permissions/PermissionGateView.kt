package com.example.bluewave_mobile.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Renders an explanation of why BlueWave needs Bluetooth permissions
 * and exposes a single CTA that re-fires the system permission dialog
 * through [BluetoothPermissionState.requestPermissions].
 *
 * The list of missing permissions is rendered with [FlowRow]: each
 * permission becomes its own assist chip and the chips wrap onto
 * additional lines when the host viewport is narrow. This is the
 * stable Compose equivalent of the original plan's Compose 1.11
 * `FlexBox(wrap = true)` surface — it matches the visual outcome
 * without depending on an experimental API.
 *
 * Pure presentation — never reads the runtime permission status
 * itself; the host composable is responsible for inspecting
 * [BluetoothPermissionState.allGranted] and choosing whether to show
 * this gate.
 *
 * @param missing Permission strings that are not yet granted. The
 *                short names (`BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`
 *                / `ACCESS_FINE_LOCATION`) are derived from the full
 *                manifest constants here so callers can pass either
 *                shape.
 * @param onGrantClick Invoked when the user taps the CTA — wire this
 *                     to [BluetoothPermissionState.requestPermissions].
 */
@Composable
fun PermissionGateView(
    missing: List<String>,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Bluetooth permission required. " +
                    "Grant Bluetooth access to discover nearby peers and exchange messages."
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Bluetooth permission required",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "BlueWave needs Bluetooth access to discover nearby peers and " +
                "exchange messages over a local radio link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (missing.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            MissingPermissionChips(
                missing = missing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrantClick) {
            Text(text = "Grant permission")
        }
    }
}

/**
 * FlowRow-backed list of assist chips, one per missing permission.
 *
 * `FlowRow` lays out its children left-to-right and wraps onto a new
 * line whenever the next chip would overflow the available width.
 * That guarantees the gate looks correct on phones in portrait
 * (3 chips, 2 lines), phones in landscape (3 chips, 1 line) and
 * tablet preview frames (3 chips, 1 line).
 *
 * Each chip shows the human-readable short name of the permission
 * (`BLUETOOTH_CONNECT`, etc.) so users can match it against the
 * exact toggle they will see in the Settings → Apps → BlueWave →
 * Permissions screen if they previously denied the runtime dialog.
 */
@Composable
private fun MissingPermissionChips(
    missing: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (permission in missing) {
                val short = permission.substringAfterLast('.')
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(text = short) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

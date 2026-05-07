package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme

/**
 * Adaptive grid that renders one [DeviceCard] per
 * [BluetoothDeviceInfo].
 *
 * Built on top of [LazyVerticalGrid] with `GridCells.Adaptive(160.dp)`
 * — Compose decides how many columns fit the current width:
 *
 *  * **portrait phones** (≤ 360 dp wide content) → 2 columns;
 *  * **landscape phones** (≤ 720 dp) → 4 columns;
 *  * **tablets / foldables** → 6+ columns.
 *
 * No manual `Configuration.screenWidthDp` checks are required, which
 * keeps the screen reactive to live `WindowSizeClass` changes such as
 * a foldable opening into tablet mode while the screen is composed.
 */
@Composable
fun DeviceGrid(
    devices: List<BluetoothDeviceInfo>,
    onDeviceClick: (BluetoothDeviceInfo) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp)
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = devices, key = { it.macAddress }) { device ->
            DeviceCard(device = device, onClick = { onDeviceClick(device) })
        }
    }
}

/**
 * Single grid cell. The whole card is clickable and announces the
 * device name + pairing status to TalkBack via a single semantics
 * node (`Role.Button`).
 */
@Composable
private fun DeviceCard(
    device: BluetoothDeviceInfo,
    onClick: () -> Unit
) {
    val contentDesc = if (device.isPaired) {
        "${device.name}, paired device"
    } else {
        "${device.name}, available device"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // mergeDescendants exposes the card to TalkBack as a
            // single Button — the device name, MAC address and
            // bonded-state icon are folded into [contentDesc]
            // explicitly, so we explicitly suppress the per-Text
            // narration that would otherwise occur.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = contentDesc
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = if (device.isPaired) {
                    Icons.Filled.BluetoothConnected
                } else {
                    Icons.Filled.Bluetooth
                },
                contentDescription = null,
                tint = if (device.isPaired) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = device.macAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (device.isPaired) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Paired",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeviceGridPreview() {
    BlueWaveTheme {
        DeviceGrid(
            devices = listOf(
                BluetoothDeviceInfo(name = "Pixel 9 Pro", macAddress = "AA:BB:CC:11:22:33", isPaired = true),
                BluetoothDeviceInfo(name = "Galaxy Z Fold", macAddress = "DD:EE:FF:44:55:66", isPaired = false),
                BluetoothDeviceInfo(name = "OnePlus 13", macAddress = "11:22:33:AA:BB:CC", isPaired = false)
            ),
            onDeviceClick = {}
        )
    }
}

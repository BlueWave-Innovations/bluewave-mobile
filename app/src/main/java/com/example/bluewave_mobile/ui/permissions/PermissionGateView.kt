package com.example.bluewave_mobile.ui.permissions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.bluewave_mobile.ui.components.EmptyStateView

/**
 * Renders an [EmptyStateView] explaining why BlueWave needs Bluetooth
 * permissions and exposes a single CTA that re-fires the system
 * permission dialog through [BluetoothPermissionState.requestPermissions].
 *
 * Pure presentation — never reads the runtime permission status
 * itself; the host composable is responsible for inspecting
 * [BluetoothPermissionState.allGranted] and choosing whether to show
 * this gate.
 *
 * @param missing Permission strings that are not yet granted (used in
 *                the explanatory text so the user knows exactly which
 *                toggles to flip in the system dialog).
 * @param onGrantClick Invoked when the user taps the CTA — wire this
 *                     to [BluetoothPermissionState.requestPermissions].
 */
@Composable
fun PermissionGateView(
    missing: List<String>,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message = if (missing.isEmpty()) {
        "BlueWave needs Bluetooth access to discover nearby peers and " +
            "exchange messages over a local radio link."
    } else {
        "BlueWave needs the following permissions to discover nearby peers " +
            "and exchange messages: " + missing.joinToString { it.substringAfterLast('.') }
    }

    EmptyStateView(
        icon = Icons.Filled.Bluetooth,
        title = "Bluetooth permission required",
        message = message,
        actionLabel = "Grant permission",
        onAction = onGrantClick,
        modifier = modifier
    )
}

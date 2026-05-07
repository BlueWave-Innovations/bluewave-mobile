package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.ui.components.DeviceGrid
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.permissions.PermissionGateView
import com.example.bluewave_mobile.ui.permissions.rememberBluetoothPermissionState
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import com.example.bluewave_mobile.ui.viewmodel.DeviceListViewModel

/**
 * Scaffold-based screen that lists Bluetooth peers and lets the user
 * jump into a one-on-one chat by tapping a device row.
 *
 * The screen forwards the user's intent (`StartScan`, `DeviceSelected`)
 * to a [DeviceListViewModel] and reacts to the resulting
 * [DeviceListUiState] snapshots. Three orthogonal gates are layered
 * before the grid is rendered:
 *
 *  1. **Runtime permissions** — shown via [PermissionGateView] until
 *     all permissions returned by `PermissionManager.missingBluetoothPermissions`
 *     are granted.
 *  2. **Bluetooth disabled** — emitted by the ViewModel when the
 *     adapter throws on `startDiscovery()`. The gate offers a
 *     "Try again" CTA that re-fires `StartScan`.
 *  3. **Empty list** — discovery completed without finding any peer.
 *     Renders the same [EmptyStateView] with a "Scan again" CTA.
 *
 * `contentWindowInsets = WindowInsets(0)` is intentional: the root
 * `Surface` in `MainActivity` already applies `systemBarsPadding()`,
 * so letting [Scaffold] re-apply its default insets would double-pad
 * the content on phones with a notch / nav bar.
 *
 * @param onDeviceClick Invoked with the MAC address of the peer the
 *                      user picked. The caller is expected to navigate
 *                      to the chat destination defined in
 *                      [com.example.bluewave_mobile.ui.navigation.ChatRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onDeviceClick: (String) -> Unit,
    viewModel: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = rememberBluetoothPermissionState()

    // Auto-start the first scan once permissions come back as granted —
    // the user shouldn't have to tap the FAB after dismissing the
    // permission dialog.
    LaunchedEffect(permissions.allGranted) {
        if (permissions.allGranted && uiState is DeviceListUiState.Idle) {
            viewModel.handleIntent(DeviceListIntent.PermissionsGranted)
            viewModel.handleIntent(DeviceListIntent.StartScan)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    // `heading()` lets TalkBack users jump between
                    // screens via the heading-navigation gesture.
                    Text(
                        text = "BlueWave",
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            if (permissions.allGranted) {
                FloatingActionButton(
                    onClick = { viewModel.handleIntent(DeviceListIntent.StartScan) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Rescan for nearby devices"
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !permissions.allGranted -> {
                    PermissionGateView(
                        missing = permissions.missingPermissions,
                        onGrantClick = { permissions.requestPermissions() }
                    )
                }
                uiState is DeviceListUiState.BluetoothDisabled -> {
                    EmptyStateView(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = "Bluetooth is turned off",
                        message = "Enable Bluetooth in system settings, then tap Try again.",
                        actionLabel = "Try again",
                        onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) }
                    )
                }
                uiState is DeviceListUiState.Error -> {
                    EmptyStateView(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = "Discovery failed",
                        message = (uiState as DeviceListUiState.Error).message,
                        actionLabel = "Retry",
                        onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) }
                    )
                }
                else -> {
                    val devices = when (val s = uiState) {
                        is DeviceListUiState.Scanning -> s.devices
                        is DeviceListUiState.Loaded -> s.devices
                        else -> emptyList()
                    }
                    if (devices.isEmpty() && uiState is DeviceListUiState.Loaded) {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                            title = "No devices nearby",
                            message = "Move closer to a paired phone or tap Scan to retry.",
                            actionLabel = "Scan again",
                            onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) }
                        )
                    } else {
                        if (uiState is DeviceListUiState.Idle) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else {
                            DeviceGrid(
                                devices = devices,
                                onDeviceClick = { device ->
                                    viewModel.handleIntent(DeviceListIntent.DeviceSelected(device.macAddress))
                                    onDeviceClick(device.macAddress)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

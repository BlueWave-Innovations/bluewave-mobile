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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.components.ContactsList
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.permissions.PermissionGateView
import com.example.bluewave_mobile.ui.permissions.rememberBluetoothPermissionState
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import com.example.bluewave_mobile.ui.viewmodel.DeviceListViewModel

/**
 * Scaffold-based screen that lists Bluetooth peers as a sectioned
 * contact list and lets the user jump into a one-on-one chat by
 * tapping a row.
 *
 * The screen forwards user intents (`StartScan`, `DeviceSelected`,
 * `SuggestInstall`) to a [DeviceListViewModel] and reacts to the
 * resulting [DeviceListUiState] snapshots. Three orthogonal gates
 * are layered before the contact list is rendered:
 *
 *  1. **Runtime permissions** — shown via [PermissionGateView] until
 *     all permissions returned by `PermissionManager.missingBluetoothPermissions`
 *     are granted.
 *  2. **Bluetooth disabled** — emitted by the ViewModel when the
 *     adapter throws on `startDiscovery()`. The gate offers a
 *     "Try again" CTA that re-fires `StartScan`.
 *  3. **Empty list** — discovery completed without finding any peer
 *     and there is no historical chat to fall back on. Renders the
 *     same [EmptyStateView] with a "Scan again" CTA.
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
 * @param onCreateGroupClick Invoked when the user taps the "create
 *                           group" CTA in the top bar; the caller is
 *                           expected to push the
 *                           [com.example.bluewave_mobile.ui.navigation.CreateGroupRoute].
 * @param onShareQrClick Invoked when the user taps the QR icon in
 *                       the top bar; the caller pushes
 *                       [com.example.bluewave_mobile.ui.navigation.QrShareRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onDeviceClick: (String) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onShareQrClick: () -> Unit = {},
    viewModel: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = rememberBluetoothPermissionState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-start the first scan once permissions come back as granted —
    // the user shouldn't have to tap the FAB after dismissing the
    // permission dialog.
    LaunchedEffect(permissions.allGranted) {
        if (permissions.allGranted && uiState is DeviceListUiState.Idle) {
            viewModel.handleIntent(DeviceListIntent.PermissionsGranted)
            viewModel.handleIntent(DeviceListIntent.StartScan)
        }
    }

    val installSuggestedMsg = stringResource(id = R.string.contacts_install_suggested)
    val installFailedMsg = stringResource(id = R.string.contacts_install_failed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DeviceListViewModel.DeviceListEvent.InstallSuggested ->
                    snackbarHostState.showSnackbar(installSuggestedMsg)
                is DeviceListViewModel.DeviceListEvent.InstallSuggestionFailed ->
                    snackbarHostState.showSnackbar(installFailedMsg)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.device_list_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            if (permissions.allGranted) {
                FloatingActionButton(
                    onClick = { viewModel.handleIntent(DeviceListIntent.StartScan) },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(
                            id = R.string.device_list_rescan_cd,
                        ),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !permissions.allGranted -> {
                    PermissionGateView(
                        missing = permissions.missingPermissions,
                        onGrantClick = { permissions.requestPermissions() },
                    )
                }
                uiState is DeviceListUiState.BluetoothDisabled -> {
                    EmptyStateView(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = stringResource(id = R.string.device_list_bluetooth_off_title),
                        message = stringResource(id = R.string.device_list_bluetooth_off_message),
                        actionLabel = stringResource(id = R.string.device_list_try_again),
                        onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) },
                    )
                }
                uiState is DeviceListUiState.Error -> {
                    EmptyStateView(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = stringResource(id = R.string.device_list_error_title),
                        message = (uiState as DeviceListUiState.Error).message,
                        actionLabel = stringResource(id = R.string.device_list_retry),
                        onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) },
                    )
                }
                else -> {
                    val rows = when (val s = uiState) {
                        is DeviceListUiState.Scanning -> s.rows
                        is DeviceListUiState.Loaded -> s.rows
                        else -> emptyList()
                    }
                    if (rows.isEmpty() && uiState is DeviceListUiState.Loaded) {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                            title = stringResource(id = R.string.device_list_empty_title),
                            message = stringResource(id = R.string.device_list_empty_message),
                            actionLabel = stringResource(id = R.string.device_list_scan_again),
                            onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) },
                        )
                    } else if (uiState is DeviceListUiState.Idle && rows.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        ContactsList(
                            rows = rows,
                            onRowClick = { mac ->
                                viewModel.handleIntent(DeviceListIntent.DeviceSelected(mac))
                                onDeviceClick(mac)
                            },
                            onSuggestInstall = { mac ->
                                viewModel.handleIntent(DeviceListIntent.SuggestInstall(mac))
                            },
                        )
                    }
                }
            }
        }
    }
}

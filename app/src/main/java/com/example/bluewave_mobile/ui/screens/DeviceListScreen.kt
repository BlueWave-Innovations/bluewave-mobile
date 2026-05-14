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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.ui.components.ContactsList
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.permissions.PermissionGateView
import com.example.bluewave_mobile.ui.permissions.rememberBluetoothPermissionState
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import com.example.bluewave_mobile.ui.strings.rememberAppStrings
import com.example.bluewave_mobile.ui.viewmodel.DeviceListViewModel

/**
 * Sectioned contact list. Renders three groups in order: existing
 * chats, peers that can chat right now, peers without the app (with
 * an Install-suggest CTA). All copy goes through [rememberAppStrings]
 * so the screen stays pure-Compose with no `R.string` lookups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onDeviceClick: (mac: String, displayName: String) -> Unit,
    viewModel: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissions = rememberBluetoothPermissionState()
    val strings = rememberAppStrings()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(permissions.allGranted) {
        if (permissions.allGranted && uiState is DeviceListUiState.Idle) {
            viewModel.handleIntent(DeviceListIntent.PermissionsGranted)
            viewModel.handleIntent(DeviceListIntent.StartScan)
        }
    }

    val installSuggestedMsg = strings.contactsInstallSuggestedSnack
    val installFailedMsg = strings.contactsInstallFailedSnack
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
                        text = strings.deviceListTitle,
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
                        contentDescription = strings.deviceListRescanCd,
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
                        title = strings.deviceListBluetoothOffTitle,
                        message = strings.deviceListBluetoothOffMessage,
                        actionLabel = strings.deviceListTryAgain,
                        onAction = { viewModel.handleIntent(DeviceListIntent.StartScan) },
                    )
                }
                uiState is DeviceListUiState.Error -> {
                    EmptyStateView(
                        icon = Icons.Filled.BluetoothDisabled,
                        title = strings.deviceListErrorTitle,
                        message = (uiState as DeviceListUiState.Error).message,
                        actionLabel = strings.deviceListRetry,
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
                            title = strings.deviceListEmptyTitle,
                            message = strings.deviceListEmptyMessage,
                            actionLabel = strings.deviceListScanAgain,
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
                            onRowClick = { mac, displayName ->
                                viewModel.handleIntent(DeviceListIntent.DeviceSelected(mac))
                                onDeviceClick(mac, displayName)
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

package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.BuiltInFolder
import com.example.bluewave_mobile.data.ChatFolderEntity
import com.example.bluewave_mobile.ui.components.ContactsList
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.model.ContactRow
import com.example.bluewave_mobile.ui.permissions.PermissionGateView
import com.example.bluewave_mobile.ui.permissions.rememberBluetoothPermissionState
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import com.example.bluewave_mobile.ui.viewmodel.DeviceListViewModel
import androidx.compose.foundation.text.KeyboardOptions

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
    onGroupClick: (String) -> Unit = {},
    viewModel: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val permissions = rememberBluetoothPermissionState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchQuery: String by rememberSaveable { mutableStateOf("") }

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
                actions = {
                    IconButton(onClick = onCreateGroupClick) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = stringResource(
                                id = R.string.device_list_create_group_cd,
                            ),
                        )
                    }
                    IconButton(onClick = onShareQrClick) {
                        Icon(
                            imageVector = Icons.Filled.QrCode,
                            contentDescription = stringResource(
                                id = R.string.device_list_qr_cd,
                            ),
                        )
                    }
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
                    val filteredRows by remember(rows, searchQuery) {
                        derivedStateOf { applySearchFilter(rows, searchQuery) }
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        SearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClear = { searchQuery = "" },
                        )
                        FolderChipRow(
                            folders = availableFolders,
                            selectedFolderId = selectedFolderId,
                            onSelect = viewModel::setFolder,
                        )
                        Box(modifier = Modifier.fillMaxSize()) {
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
                            } else if (filteredRows.isEmpty() && searchQuery.isNotBlank()) {
                                EmptyStateView(
                                    icon = Icons.Filled.Search,
                                    title = stringResource(id = R.string.search_empty_title),
                                    message = stringResource(id = R.string.search_empty_message),
                                    actionLabel = stringResource(id = R.string.search_clear),
                                    onAction = { searchQuery = "" },
                                )
                            } else {
                                ContactsList(
                                    rows = filteredRows,
                                    onRowClick = { mac ->
                                        viewModel.handleIntent(DeviceListIntent.DeviceSelected(mac))
                                        onDeviceClick(mac)
                                    },
                                    onSuggestInstall = { mac ->
                                        viewModel.handleIntent(DeviceListIntent.SuggestInstall(mac))
                                    },
                                    onGroupClick = onGroupClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontally scrollable chip row that drives the active folder
 * filter. Three categories of chips:
 *  * **All** — synthetic, encoded as `null` in
 *    [DeviceListViewModel.selectedFolderId]; surfaces the unfiltered
 *    contact list. Always rendered first.
 *  * **Nearby** — synthetic, encoded as
 *    [DeviceListViewModel.VIRTUAL_NEARBY_ID]; trims chats to peers
 *    currently visible on the radio.
 *  * **Persistent folders** — one chip per row in [folders],
 *    rendered in the order returned by
 *    [com.example.bluewave_mobile.data.FolderRepository.observeFolders].
 *    Built-in keys map to localised resources so "Work" reads as
 *    "Работа" / "Work" depending on locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderChipRow(
    folders: List<ChatFolderEntity>,
    selectedFolderId: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        item(key = "chip:all") {
            FilterChip(
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
                label = { Text(text = stringResource(id = R.string.folders_chip_all)) },
            )
        }
        item(key = "chip:nearby") {
            FilterChip(
                selected = selectedFolderId == DeviceListViewModel.VIRTUAL_NEARBY_ID,
                onClick = { onSelect(DeviceListViewModel.VIRTUAL_NEARBY_ID) },
                label = { Text(text = stringResource(id = R.string.folders_chip_nearby)) },
            )
        }
        items(items = folders, key = { "chip:" + it.id }) { folder ->
            val label = when (folder.builtInKey) {
                BuiltInFolder.WORK -> stringResource(id = R.string.folders_builtin_work)
                BuiltInFolder.FAMILY -> stringResource(id = R.string.folders_builtin_family)
                else -> folder.name.ifBlank { folder.id }
            }
            FilterChip(
                selected = selectedFolderId == folder.id,
                onClick = { onSelect(folder.id) },
                label = { Text(text = label) },
            )
        }
    }
}

/**
 * Search field that lives directly under the top app bar and feeds
 * its plaintext into [applySearchFilter].
 *
 * Uses a single-line [OutlinedTextField] rather than the `SearchBar`
 * component so the chip-row beneath it can keep its existing
 * spacing — the BlueWave list is dense and a full Material 3
 * `SearchBar` would push the first chat row below the fold on
 * compact phones. The leading icon is a magnifier, the trailing
 * icon is a clear button shown only while the field is non-empty.
 *
 * Search runs locally — no network round-trip — and is fed by the
 * already-streaming row list, so we don't add any IO. The
 * [ImeAction.Search] keyboard hint is purely cosmetic; pressing it
 * dismisses the soft keyboard but doesn't fire any extra effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(text = stringResource(id = R.string.search_placeholder))
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(id = R.string.search_clear),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Pure projection of the row list against the user-typed search
 * [query].
 *
 *  * Empty / blank queries pass every row through unchanged so the
 *    happy path has zero overhead.
 *  * The match is case-insensitive and trimmed; we lowercase both
 *    sides under [java.util.Locale.ROOT] so locale-specific casing
 *    rules (e.g. Turkish dotless i) don't surprise users.
 *  * For [ContactRow.ExistingChat] / [ContactRow.GroupChat] we also
 *    match against the cached `lastMessagePreview` so typing a word
 *    from a recent message brings the chat row up — mirrors the
 *    behaviour of every consumer messenger.
 */
internal fun applySearchFilter(
    rows: List<ContactRow>,
    query: String,
): List<ContactRow> {
    val needle = query.trim()
    if (needle.isEmpty()) return rows
    val lowered = needle.lowercase()
    return rows.filter { row ->
        if (row.displayName.lowercase().contains(lowered)) return@filter true
        if (row.macAddress.lowercase().contains(lowered)) return@filter true
        when (row) {
            is ContactRow.ExistingChat -> row.lastMessagePreview.lowercase().contains(lowered)
            is ContactRow.GroupChat -> row.lastMessagePreview.lowercase().contains(lowered)
            else -> false
        }
    }
}

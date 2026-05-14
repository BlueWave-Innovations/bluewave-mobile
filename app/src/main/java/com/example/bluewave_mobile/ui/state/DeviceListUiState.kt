package com.example.bluewave_mobile.ui.state

import com.example.bluewave_mobile.ui.model.ContactRow

/**
 * Exhaustive snapshot of the device-list screen.
 *
 * Mirrors the design of [ChatUiState] — every branch is mutually
 * exclusive and can be rendered without referencing fields from any
 * other branch.
 *
 *  * [Idle] — discovery has not been started yet (initial state and
 *    the state we fall back to between scans).
 *  * [Scanning] — `BluetoothAdapter.startDiscovery()` is currently
 *    running. Carries the running list of sectioned [ContactRow]s
 *    observed so far so the UI updates incrementally as new peers
 *    appear or new chats are persisted.
 *  * [Loaded] — discovery finished or was cancelled and we have a
 *    final snapshot of the contact list.
 *  * [PermissionRequired] — the user has not granted the runtime
 *    permissions required by the current `Build.VERSION.SDK_INT` —
 *    see `PermissionManager.missingBluetoothPermissions`.
 *  * [BluetoothDisabled] — the adapter is `null` or
 *    `isEnabled == false`. The UI should show an action that opens
 *    the system Bluetooth settings.
 *  * [Error] — terminal failure path for unrecoverable I/O / radio
 *    faults.
 *
 * The contact rows are presented as one flat list because the UI
 * layer is responsible for splitting them into the three sections —
 * "Chats", "Can start chat", "No app yet" — by branching on the
 * [ContactRow] subtype. Keeping the state class agnostic of
 * presentation lets future variants (search, filter) reuse it.
 */
sealed interface DeviceListUiState {
    data object Idle : DeviceListUiState

    data class Scanning(val rows: List<ContactRow>) : DeviceListUiState

    data class Loaded(val rows: List<ContactRow>) : DeviceListUiState

    data class PermissionRequired(val missing: List<String>) : DeviceListUiState

    data object BluetoothDisabled : DeviceListUiState

    data class Error(val message: String) : DeviceListUiState
}

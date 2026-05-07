package com.example.bluewave_mobile.ui.state

import com.example.bluewave_mobile.data.BluetoothDeviceInfo

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
 *    running. Carries the running list of devices observed so far so
 *    the UI can update incrementally.
 *  * [Loaded] — discovery finished or was cancelled and we have a
 *    final list of [BluetoothDeviceInfo].
 *  * [PermissionRequired] — the user has not granted the runtime
 *    permissions required by the current `Build.VERSION.SDK_INT` —
 *    see `PermissionManager.missingBluetoothPermissions`.
 *  * [BluetoothDisabled] — the adapter is `null` or
 *    `isEnabled == false`. The UI should show an action that opens
 *    the system Bluetooth settings.
 *  * [Error] — terminal failure path for unrecoverable I/O / radio
 *    faults.
 */
sealed interface DeviceListUiState {
    data object Idle : DeviceListUiState

    data class Scanning(val devices: List<BluetoothDeviceInfo>) : DeviceListUiState

    data class Loaded(val devices: List<BluetoothDeviceInfo>) : DeviceListUiState

    data class PermissionRequired(val missing: List<String>) : DeviceListUiState

    data object BluetoothDisabled : DeviceListUiState

    data class Error(val message: String) : DeviceListUiState
}

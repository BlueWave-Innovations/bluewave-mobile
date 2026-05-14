package com.example.bluewave_mobile.ui.intent

/**
 * User-driven actions the device-list screen can fire towards
 * `DeviceListViewModel`.
 *
 * Counterpart of [ChatIntent] for the device discovery screen. Each
 * branch is a pure data class so it can be matched exhaustively in
 * `when` expressions inside the reducer.
 */
sealed interface DeviceListIntent {
    /** Begin a fresh `BluetoothAdapter.startDiscovery()` cycle. */
    data object StartScan : DeviceListIntent

    /** Cancel an in-flight scan (e.g. user navigated away). */
    data object StopScan : DeviceListIntent

    /** User granted at least one previously-missing runtime permission. */
    data object PermissionsGranted : DeviceListIntent

    /** User tapped a row — the chat destination should be opened. */
    data class DeviceSelected(val macAddress: String) : DeviceListIntent

    /**
     * User tapped the "Suggest install" CTA on a peer that is visible
     * on the radio but does not advertise the BlueWave SDP UUID. The
     * VM hands the staged APK to the system Bluetooth share UI via
     * [com.example.bluewave_mobile.network.ApkSender.suggestInstall].
     */
    data class SuggestInstall(val macAddress: String) : DeviceListIntent
}

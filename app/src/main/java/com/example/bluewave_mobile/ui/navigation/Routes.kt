package com.example.bluewave_mobile.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object DeviceListRoute

/**
 * Navigation destination for the per-peer chat screen.
 *
 * Carries both the MAC address (used internally as the RFCOMM key —
 * `BluetoothAdapter.getRemoteDevice` only accepts a MAC, there is no
 * way to address a peer by friendly name on the classic Bluetooth
 * stack) and the user-visible display name. The display name is what
 * the chat screen TopAppBar shows; the MAC is plumbed straight
 * through to the transport.
 *
 * `deviceName` defaults to an empty string for resilience against
 * old saved-state restores from before this field was added. The
 * chat screen falls back to the MAC when the name is blank so the
 * user never sees an empty title.
 */
@Serializable
data class ChatRoute(
    val deviceMac: String,
    val deviceName: String = "",
)

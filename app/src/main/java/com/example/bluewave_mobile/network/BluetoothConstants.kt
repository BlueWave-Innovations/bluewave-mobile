package com.example.bluewave_mobile.network

import java.util.UUID

/**
 * Network-layer constants shared by [AcceptThread] and the (future)
 * `ConnectThread` (step 17). Centralising them avoids string drift between
 * server and client side.
 */
internal object BluetoothConstants {

    /**
     * SDP service name advertised by the RFCOMM server socket.
     */
    const val SERVICE_NAME: String = "BlueWaveRFCOMM"

    /**
     * Application-specific UUID used by both the server and client RFCOMM
     * sockets. Two BlueWave instances will only successfully connect if they
     * agree on the exact same UUID — changing this value is a breaking
     * protocol change.
     */
    val APP_UUID: UUID = UUID.fromString("3f1c8a72-7e2c-4f4d-9b40-6d5b1f8b9d31")
}

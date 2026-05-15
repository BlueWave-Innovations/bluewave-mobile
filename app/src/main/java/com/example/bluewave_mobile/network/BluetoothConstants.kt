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

    /**
     * Cadence of the transport-level heartbeat ping (`BlueWaveFrame.Type.HEARTBEAT`).
     *
     * Three seconds is short enough that a peer disappearing — BT toggled
     * off, app killed, screen-off radio suspension — flips the online dot
     * within roughly two heartbeat intervals, matching the "instant"
     * behaviour the user expects from Telegram-class messengers, but
     * long enough that the radio cost of always-on chatter stays in the
     * single-digit-milliwatt range that Android docs quote for an idle
     * RFCOMM socket.
     */
    const val HEARTBEAT_INTERVAL_MS: Long = 3_000L

    /**
     * Liveness window: if no frame of any kind arrives from the peer
     * within this window the session is considered dead and torn down,
     * which evicts the MAC from `connectedPeers` and triggers the
     * libsignal session reset (see
     * `MessageRepositoryImpl.onPeerLinkDown`).
     *
     * Three missed heartbeats (≈9s) plus a one-second jitter buffer
     * matches the user-visible "few seconds to disappear" target while
     * staying robust to a single dropped ping caused by a transient
     * scan or audio-stream priority inversion on the radio chipset.
     */
    const val LIVENESS_TIMEOUT_MS: Long = 10_000L

    /**
     * Maximum back-off between successive attempts to (re-)open the
     * RFCOMM server socket inside the accept loop.
     *
     * The loop retries every [ACCEPT_RETRY_DELAY_MS] when the adapter
     * is off or `listenUsingRfcomm` fails — short enough that flipping
     * Bluetooth back on brings the listen socket up "instantly" from a
     * user perspective, long enough not to spin the radio when the
     * adapter stays disabled for minutes.
     */
    const val ACCEPT_RETRY_DELAY_MS: Long = 2_000L
}

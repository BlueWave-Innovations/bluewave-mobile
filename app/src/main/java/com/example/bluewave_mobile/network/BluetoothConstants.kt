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

    /**
     * Exponential back-off schedule applied between successive
     * `socket.connect()` retries for a single outbound connection
     * attempt.
     *
     * Order is `[after-attempt-1, after-attempt-2, after-attempt-3]`,
     * which yields a worst-case 1 + 3 + 8 = 12 s window during which
     * a peer that just came online can race in. This closes the
     * cold-launch race where two BlueWave phones light up their
     * accept loops at slightly different times and the very first
     * outbound connect lands while the remote `listenUsingRfcomm`
     * hasn't published its SDP record yet.
     *
     * Tuned to match the canonical "instant messenger" UX: under a
     * second on the happy path, ~12 s upper bound before we give up
     * and fall back to the per-`sessionDetached` reconnect loop in
     * [com.example.bluewave_mobile.BlueWaveApplication].
     */
    val CONNECT_RETRY_BACKOFFS_MS: List<Long> = listOf(1_000L, 3_000L, 8_000L)

    /**
     * Hard deadline for a [BluetoothDevice.fetchUuidsWithSdp] probe.
     *
     * Android's SDP cache lives in the platform Bluetooth process
     * and can hold a stale "service not found" entry for a paired
     * peer indefinitely. To keep the device-list UX deterministic
     * we treat any probe that does not deliver an `ACTION_UUID`
     * broadcast within [SDP_PROBE_TIMEOUT_MS] as a definitive "this
     * peer does NOT run BlueWave" answer — the row drops into the
     * "No app yet" section and the install-suggestion CTA lights up.
     *
     * 2 s matches the platform-side BR/EDR inquiry budget; longer
     * deadlines feel laggy on-device (the user already saw the row
     * appear and expects the section badge to settle immediately),
     * shorter ones flap on slow devices.
     */
    const val SDP_PROBE_TIMEOUT_MS: Long = 2_000L
}

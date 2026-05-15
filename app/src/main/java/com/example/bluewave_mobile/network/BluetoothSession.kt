package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Live RFCOMM session with a single peer.
 *
 * A [BluetoothSession] wraps a connected [BluetoothSocket] together
 * with the framing logic that turns the raw byte stream into discrete
 * payloads. It owns a [ConnectedThread] for the actual I/O and a
 * [FrameAccumulator] for the wire-format reassembly.
 *
 * Lifecycle (driven externally by [BluetoothSessionManager]):
 *
 *  1. Construct with the connected socket.
 *  2. Call [start] to launch the read loop.
 *  3. Use [send] to push frames to the peer.
 *  4. Call [cancel] when the manager evicts this peer or when the
 *     read loop terminates (peer closed the stream / I/O error).
 *
 * The class is intentionally NOT exposed via the public
 * [MessageTransport] interface — callers always go through the
 * session manager so that there is exactly one source of truth for
 * "which peers are live".
 *
 * **Liveness.** RFCOMM does not surface a TCP-style "the other end
 * went away" event reliably — if the peer turns Bluetooth off, kills
 * its process or just walks out of range, our `InputStream.read` can
 * sit blocked for minutes before the kernel notices. To match the
 * "instant offline" UX of a centralised messenger we maintain two
 * helper jobs alongside the read loop:
 *
 *  * a periodic **heartbeat sender** writing a
 *    [BlueWaveFrame.Type.HEARTBEAT] frame every
 *    [BluetoothConstants.HEARTBEAT_INTERVAL_MS] millis. A failed
 *    write tears the session down immediately;
 *  * a **liveness watchdog** that checks the timestamp of the last
 *    *received* byte chunk every
 *    [BluetoothConstants.HEARTBEAT_INTERVAL_MS] millis and tears
 *    the session down once the gap exceeds
 *    [BluetoothConstants.LIVENESS_TIMEOUT_MS].
 *
 * Heartbeat frames are filtered out before they reach `onFrame` so
 * the application layer remains unaware of the liveness machinery.
 */
internal class BluetoothSession(
    private val socket: BluetoothSocket,
    private val connectedThread: ConnectedThread = ConnectedThread(socket),
    private val accumulator: FrameAccumulator = FrameAccumulator(),
) {
    /**
     * Uppercase MAC of the remote peer. Stored eagerly so callers can
     * key into per-peer maps without having to re-resolve the address
     * (which costs a binder call on real Android).
     */
    @SuppressLint("MissingPermission")
    val remoteMacAddress: String = socket.remoteDevice?.address?.uppercase().orEmpty()

    /**
     * Best-effort human-readable name of the peer. Falls back to the
     * MAC address when the platform refuses to surface the friendly
     * name (e.g. when [android.Manifest.permission.BLUETOOTH_CONNECT]
     * has not been granted yet).
     */
    val remoteName: String = try {
        @SuppressLint("MissingPermission")
        socket.remoteDevice?.name?.takeIf { it.isNotBlank() } ?: remoteMacAddress
    } catch (e: SecurityException) {
        BlueWaveLogger.d(TAG, "BluetoothDevice.name denied by permissions, falling back to MAC", e)
        remoteMacAddress
    }

    private var pumpJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null

    /**
     * Elapsed-realtime timestamp of the most recent byte chunk read
     * from the peer — refreshed by the read loop and polled by the
     * liveness watchdog. Uses [SystemClock.elapsedRealtime] so NTP
     * skew cannot cause false-positive watchdog trips.
     */
    @Volatile
    private var lastInboundElapsedMs: Long = SystemClock.elapsedRealtime()

    /**
     * Elapsed-realtime timestamp of the last heartbeat *send*.
     * Reset to `0L` after an RTT sample is taken so [lastPingMs]
     * only measures the interval between a heartbeat and the
     * *next* inbound frame, not every arbitrary chunk.
     */
    @Volatile
    private var lastHeartbeatSendElapsedMs: Long = 0L

    /**
     * Most recently measured round-trip latency in milliseconds.
     * Updated every time the watchdog observes a fresh inbound
     * frame after a heartbeat was sent. Zero until the first
     * measurement. Negative values are clamped to zero.
     */
    @Volatile
    var lastPingMs: Long = 0L
        private set

    /**
     * Stream of byte chunks straight from the underlying
     * [ConnectedThread]. Exposed so [start] can subscribe to it after
     * the read loop is launched — external callers should never use
     * this; they get framed payloads through the session manager.
     */
    private val incomingBytes: SharedFlow<ByteArray>
        get() = connectedThread.incomingBytes

    private val startLock = Any()

    /**
     * Launches the read loop and forwards each fully-reassembled frame
     * to [onFrame]. Returns immediately. The session is idempotent —
     * calling [start] twice is a no-op.
     */
    fun start(scope: CoroutineScope, onFrame: suspend (ByteArray) -> Unit, onClosed: suspend () -> Unit) {
        synchronized(startLock) {
            if (pumpJob != null) return
            connectedThread.start()
            lastInboundElapsedMs = SystemClock.elapsedRealtime()
        }
        pumpJob = scope.launch {
            try {
                incomingBytes.collect { chunk ->
                    // Any byte from the peer — even a partial frame
                    // header — proves the link is alive, so refresh
                    // the watchdog timestamp before parsing.
                    val now = SystemClock.elapsedRealtime()
                    lastInboundElapsedMs = now
                    // Approximate RTT: time between our last heartbeat
                    // send and the next inbound frame (which should be
                    // the peer's heartbeat reply or data).
                    val hbSend = lastHeartbeatSendElapsedMs
                    if (hbSend > 0L) {
                        lastPingMs = (now - hbSend).coerceAtLeast(0L)
                        lastHeartbeatSendElapsedMs = 0L
                    }
                    val frames = try {
                        accumulator.append(chunk)
                    } catch (e: IllegalStateException) {
                        BlueWaveLogger.w(TAG, "Protocol error from $remoteMacAddress: ${e.message}; tearing down session")
                        cancel()
                        return@collect
                    }
                    for (frame in frames) {
                        if (isHeartbeat(frame)) {
                            // `lastInboundElapsedMs` already refreshed above;
                            // drop the frame so the application layer
                            // never has to know heartbeats exist.
                            continue
                        }
                        onFrame(frame)
                    }
                }
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                watchdogJob?.cancel()
                watchdogJob = null
                runCatching { onClosed() }
            }
        }
        heartbeatJob = scope.launch { runHeartbeatSender() }
        watchdogJob = scope.launch { runLivenessWatchdog() }
    }

    /**
     * Periodically pushes a zero-body
     * [BlueWaveFrame.Type.HEARTBEAT] frame to the peer. A failed
     * write means the socket is already dead and we tear the
     * session down so [BluetoothSessionManager.connectedPeers]
     * flips off immediately — without waiting for the read loop to
     * notice on the next `input.read()` call (which can be many
     * seconds away).
     */
    private suspend fun runHeartbeatSender() {
        val pingFrame = BlueWaveFrame.encode(BlueWaveFrame.Type.HEARTBEAT, EMPTY_PAYLOAD)
        while (true) {
            delay(BluetoothConstants.HEARTBEAT_INTERVAL_MS)
            lastHeartbeatSendElapsedMs = SystemClock.elapsedRealtime()
            val framed = try {
                MessageFraming.frame(pingFrame)
            } catch (e: IllegalArgumentException) {
                // The heartbeat is a constant 1-byte payload so this
                // branch is unreachable in practice — log defensively
                // and stop pinging if it ever fires.
                BlueWaveLogger.e(TAG, "Refusing to frame heartbeat for $remoteMacAddress: ${e.message}")
                return
            }
            val ok = connectedThread.write(framed)
            if (!ok) {
                BlueWaveLogger.d(TAG, "Heartbeat write failed for $remoteMacAddress; tearing down session")
                cancel()
                return
            }
        }
    }

    /**
     * Watchdog that wakes up every
     * [BluetoothConstants.HEARTBEAT_INTERVAL_MS] and tears the
     * session down once we have not seen a single byte from the
     * peer for more than [BluetoothConstants.LIVENESS_TIMEOUT_MS] —
     * the radio link is functionally dead even if the kernel has
     * not noticed yet.
     */
    private suspend fun runLivenessWatchdog() {
        while (true) {
            delay(BluetoothConstants.HEARTBEAT_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            if (now - lastInboundElapsedMs > BluetoothConstants.LIVENESS_TIMEOUT_MS) {
                BlueWaveLogger.d(
                    TAG,
                    "Liveness watchdog tripped for $remoteMacAddress " +
                        "(${now - lastInboundElapsedMs} ms since last inbound byte); tearing down session",
                )
                cancel()
                return
            }
        }
    }

    /**
     * Frames [payload] with the BlueWave length prefix and writes it
     * to the underlying socket. Returns `false` when the socket is
     * already closed or the write failed — the session manager is
     * expected to drop this session in that case.
     */
    suspend fun send(payload: ByteArray): Boolean {
        val framed = try {
            MessageFraming.frame(payload)
        } catch (e: IllegalArgumentException) {
            BlueWaveLogger.w(TAG, "Refusing oversized payload to $remoteMacAddress: ${e.message}")
            return false
        }
        return connectedThread.write(framed)
    }

    /**
     * Closes the socket, cancels the pump and tears down the
     * underlying [ConnectedThread]. Safe to call multiple times.
     */
    fun cancel() {
        pumpJob?.cancel()
        pumpJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        connectedThread.cancel()
    }

    /**
     * `true` iff [frame] decodes to a [BlueWaveFrame.Type.HEARTBEAT].
     * Cheap one-byte inspection — avoids paying the cost of the full
     * `decode` path on every inbound frame, since the type tag is
     * always the first byte.
     */
    private fun isHeartbeat(frame: ByteArray): Boolean =
        frame.isNotEmpty() && frame[0] == BlueWaveFrame.Type.HEARTBEAT.tag

    private companion object {
        const val TAG = "BluetoothSession"
        val EMPTY_PAYLOAD: ByteArray = ByteArray(0)
    }
}

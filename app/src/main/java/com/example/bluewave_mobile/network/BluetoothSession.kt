package com.example.bluewave_mobile.network

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    val remoteMacAddress: String = socket.remoteDevice?.address?.uppercase().orEmpty()

    /**
     * Best-effort human-readable name of the peer. Falls back to the
     * MAC address when the platform refuses to surface the friendly
     * name (e.g. when [android.Manifest.permission.BLUETOOTH_CONNECT]
     * has not been granted yet).
     */
    val remoteName: String = try {
        @Suppress("MissingPermission")
        socket.remoteDevice?.name?.takeIf { it.isNotBlank() } ?: remoteMacAddress
    } catch (e: SecurityException) {
        Log.d(TAG, "BluetoothDevice.name denied by permissions, falling back to MAC", e)
        remoteMacAddress
    }

    private var pumpJob: Job? = null

    /**
     * Stream of byte chunks straight from the underlying
     * [ConnectedThread]. Exposed so [start] can subscribe to it after
     * the read loop is launched — external callers should never use
     * this; they get framed payloads through the session manager.
     */
    private val incomingBytes: SharedFlow<ByteArray>
        get() = connectedThread.incomingBytes

    /**
     * Launches the read loop and forwards each fully-reassembled frame
     * to [onFrame]. Returns immediately. The session is idempotent —
     * calling [start] twice is a no-op.
     */
    fun start(scope: CoroutineScope, onFrame: suspend (ByteArray) -> Unit, onClosed: suspend () -> Unit) {
        if (pumpJob != null) return
        connectedThread.start()
        pumpJob = scope.launch {
            try {
                incomingBytes.collect { chunk ->
                    val frames = try {
                        accumulator.append(chunk)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Protocol error from $remoteMacAddress: ${e.message}; tearing down session")
                        cancel()
                        return@collect
                    }
                    for (frame in frames) {
                        onFrame(frame)
                    }
                }
            } finally {
                onClosed()
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
            Log.w(TAG, "Refusing oversized payload to $remoteMacAddress: ${e.message}")
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
        connectedThread.cancel()
    }

    private companion object {
        const val TAG = "BluetoothSession"
    }
}

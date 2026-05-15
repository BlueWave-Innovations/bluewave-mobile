package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Outgoing-connection counterpart of [AcceptThread].
 *
 * When the user picks a device from the discovery list, [ConnectThread]
 * is responsible for opening an RFCOMM client socket against the chosen
 * peer using the shared [BluetoothConstants.APP_UUID].
 *
 * ## Why `cancelDiscovery()` first
 *
 * `BluetoothAdapter.startDiscovery()` is an extremely heavyweight radio
 * operation that occupies the chipset for ~12 seconds. Leaving it
 * running while we attempt a connection causes `connect()` to either
 * fail intermittently with `IOException` or take an order of magnitude
 * longer than necessary — Google's official Bluetooth developer guide
 * explicitly warns about this. [start] therefore always invokes
 * [BluetoothAdapter.cancelDiscovery] before calling `connect()`.
 *
 * ## Structured Concurrency
 *
 * The class accepts an externally-supplied [CoroutineScope] (defaults
 * to one produced by [BluetoothScopeFactory.createNetworkScope], which
 * combines a [kotlinx.coroutines.SupervisorJob],
 * [kotlinx.coroutines.Dispatchers.IO] and a shared
 * [kotlinx.coroutines.CoroutineExceptionHandler]). This guarantees:
 *
 *  * a failed connection does NOT cancel sibling coroutines in the
 *    repository — the radio link dropping is an expected condition;
 *  * the blocking `connect()` call runs on [Dispatchers.IO];
 *  * uncaught exceptions are logged through the shared handler instead
 *    of crashing the app.
 *
 * ## Lifecycle
 *
 * Cancellation must go through [cancel], which closes the underlying
 * client socket (unblocking any in-flight `connect()`), nulls out the
 * reference and cancels the coroutine scope inside a `try/finally` so
 * the scope is **always** torn down — even when `socket.close()` itself
 * throws.
 */
class ConnectThread(
    private val adapter: BluetoothAdapter?,
    private val device: BluetoothDevice,
    private val onConnected: suspend (BluetoothSocket) -> Unit,
    private val onConnectionFailed: suspend (Throwable) -> Unit = {},
    private val scope: CoroutineScope = BluetoothScopeFactory.createNetworkScope()
) {

    @Volatile
    private var clientSocket: BluetoothSocket? = null

    /**
     * Initiates the outgoing connection. Returns immediately; the
     * blocking `connect()` runs on [Dispatchers.IO].
     */
    @SuppressLint("MissingPermission")
    fun start() {
        scope.launch {
            val localAdapter = adapter ?: run {
                onConnectionFailed(IllegalStateException("BluetoothAdapter unavailable"))
                return@launch
            }

            // Discovery is heavyweight — it MUST be cancelled before connecting,
            // otherwise the connect() call may stall or fail intermittently.
            try {
                if (localAdapter.isDiscovering) {
                    localAdapter.cancelDiscovery()
                }
            } catch (e: SecurityException) {
                BlueWaveLogger.w(TAG, "cancelDiscovery() denied by permissions", e)
            }

            val socket: BluetoothSocket = try {
                device.createRfcommSocketToServiceRecord(BluetoothConstants.APP_UUID)
            } catch (e: IOException) {
                onConnectionFailed(e)
                return@launch
            }
            clientSocket = socket

            try {
                socket.connect()
                onConnected(socket)
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "connect() failed for ${device.address}: ${e.message}")
                try { socket.close() } catch (_: IOException) { /* best effort */ }
                clientSocket = null
                onConnectionFailed(e)
            }
        }
    }

    /**
     * Aborts an in-flight connection attempt by closing the underlying
     * socket. Safe to call from any thread; the launched coroutine will
     * exit through its IOException catch block.
     *
     * The close()/cancel() sequence is wrapped in try/finally so the
     * coroutine scope is **always** cancelled even when socket.close()
     * throws — which happens routinely on Android when the radio link
     * was already torn down by the peer or by the OS bond loss handler.
     */
    fun cancel() {
        try {
            try {
                clientSocket?.close()
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Error closing client socket", e)
            }
        } finally {
            clientSocket = null
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "ConnectThread"
    }
}

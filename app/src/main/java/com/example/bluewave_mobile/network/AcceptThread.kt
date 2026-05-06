package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Listens for incoming RFCOMM connections on a server socket.
 *
 * The classic Bluetooth stack on Android emulates a serial port: one device
 * has to open a server socket via
 * [BluetoothAdapter.listenUsingRfcommWithServiceRecord] and the other one
 * connects to the same UUID. [AcceptThread] performs the *server* half of
 * that handshake.
 *
 * A single [AcceptThread] handles **one** accepted connection at a time
 * — once a client connects, the server socket is closed and the produced
 * [BluetoothSocket] is forwarded to [onSocketAccepted]. Re-listening is the
 * caller's responsibility (typically by spawning a new [AcceptThread] after
 * the previous connection is torn down).
 *
 * All I/O happens on [Dispatchers.IO]; cancellation must be done via
 * [cancel] which closes the underlying server socket and unblocks the
 * `accept()` call.
 */
class AcceptThread(
    private val adapter: BluetoothAdapter?,
    private val onSocketAccepted: suspend (BluetoothSocket) -> Unit,
    private val scope: CoroutineScope = BluetoothScopeFactory.createNetworkScope()
) {

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    /**
     * Starts listening on a fresh RFCOMM server socket. Returns immediately
     * — the actual blocking `accept()` runs on [Dispatchers.IO].
     */
    @SuppressLint("MissingPermission")
    fun start() {
        scope.launch {
            val localAdapter = adapter ?: run {
                Log.w(TAG, "BluetoothAdapter unavailable; cannot listen for incoming connections")
                return@launch
            }
            try {
                val socket = localAdapter.listenUsingRfcommWithServiceRecord(
                    BluetoothConstants.SERVICE_NAME,
                    BluetoothConstants.APP_UUID
                )
                serverSocket = socket
                while (isActive) {
                    val accepted: BluetoothSocket = try {
                        socket.accept()
                    } catch (e: IOException) {
                        Log.d(TAG, "Server socket closed: ${e.message}")
                        break
                    }
                    // Stop accepting further connections — only one peer at a time.
                    try { socket.close() } catch (_: IOException) { /* already closed */ }
                    onSocketAccepted(accepted)
                    break
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to open RFCOMM server socket", e)
            }
        }
    }

    /**
     * Cancels the listening loop, closes the server socket and tears down
     * the supervisor scope. Safe to call multiple times — the underlying
     * `close()` is wrapped in a try/finally so the scope is **always**
     * cancelled even if the close throws.
     *
     * Calling this from `Activity.onDestroy()` (or the matching ViewModel
     * `onCleared()`) is the canonical way to prevent leaking the
     * BluetoothServerSocket file descriptor across configuration changes.
     */
    fun cancel() {
        try {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error while closing server socket", e)
            }
        } finally {
            serverSocket = null
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "AcceptThread"
    }
}

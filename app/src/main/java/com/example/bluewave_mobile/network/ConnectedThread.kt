package com.example.bluewave_mobile.network

import android.bluetooth.BluetoothSocket
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Drives the bidirectional byte stream over an established
 * [BluetoothSocket].
 *
 * Once [AcceptThread] or [ConnectThread] hands a connected socket here,
 * [ConnectedThread] takes ownership of:
 *
 *  * its [InputStream] — read in a non-blocking loop on [Dispatchers.IO];
 *    every received chunk is re-emitted through [incomingBytes]
 *    ([SharedFlow]) so multiple subscribers (UI, persistence) can observe
 *    the same data;
 *  * its [OutputStream] — guarded by [writeMutex] so that several
 *    coroutines calling [write] cannot interleave bytes.
 *
 * Cancellation closes both streams and the socket, which immediately
 * unblocks the read loop with an [IOException] that we treat as a normal
 * end-of-stream signal.
 */
class ConnectedThread(
    private val socket: BluetoothSocket,
    private val scope: CoroutineScope = BluetoothScopeFactory.createNetworkScope(),
    private val readBufferSize: Int = DEFAULT_BUFFER_BYTES
) {
    private val writeMutex: Mutex = Mutex()

    private val _incomingBytes: MutableSharedFlow<ByteArray> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)

    /**
     * Hot stream of byte chunks as they arrive from the remote peer.
     * One emission corresponds to one successful `read()` call; the
     * caller is responsible for any framing or message reassembly.
     */
    val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    /**
     * Starts the read loop in the background. Returns immediately.
     */
    fun start() {
        scope.launch {
            val input = try {
                socket.inputStream
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Unable to obtain input stream", e)
                return@launch
            }
            val buffer = ByteArray(readBufferSize)
            while (isActive) {
                val read: Int = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    BlueWaveLogger.d(TAG, "Read loop terminated: ${e.message}")
                    break
                }
                if (read <= 0) {
                    // Peer closed the stream; exit cleanly.
                    break
                }
                _incomingBytes.emit(buffer.copyOf(read))
            }
        }
    }

    /**
     * Sends [bytes] to the remote peer. Suspends until the OutputStream
     * accepts the data and the [writeMutex] guarantees serialised writes
     * even when called concurrently.
     *
     * @return `true` if the data was flushed successfully, `false` if the
     *         underlying socket has already been closed or errored.
     */
    suspend fun write(bytes: ByteArray): Boolean = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val output: OutputStream = socket.outputStream
                // RFCOMM socket buffers are small on many chipsets;
                // writing the entire frame at once can overflow the TX
                // queue and cause silent truncation. We stream in 8 KiB
                // slices so the Bluetooth stack can pace the transfer.
                val chunkSize = 8192
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + chunkSize, bytes.size)
                    output.write(bytes, offset, end - offset)
                    offset = end
                }
                output.flush()
                true
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Write failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Cancels the read loop and closes the underlying [BluetoothSocket]
     * along with both its [InputStream] and [OutputStream]. Idempotent.
     *
     * Each of the three close calls is independently wrapped in a
     * try/catch so that one failing close does not prevent the others.
     * The coroutine scope is cancelled inside the outer `finally`, which
     * means it is **always** torn down even when one or more streams
     * throw on close — without that guarantee the read loop would
     * remain pinned in memory and leak a file descriptor.
     */
    fun cancel() {
        try {
            try {
                socket.inputStream?.close()
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Error closing input stream", e)
            }
            try {
                socket.outputStream?.close()
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Error closing output stream", e)
            }
            try {
                socket.close()
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Error closing socket", e)
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TAG = "ConnectedThread"
        const val DEFAULT_BUFFER_BYTES = 32768
    }
}

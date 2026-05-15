package com.example.bluewave_mobile.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefix framing for the BlueWave wire protocol.
 *
 * RFCOMM is a stream-oriented transport: a single `OutputStream.write`
 * on the sender side may surface as one, several or partial reads on
 * the peer. To recover discrete message boundaries we prepend each
 * payload with a 4-byte big-endian length field:
 *
 * ```
 * +----------+--------------------+
 * | 4B BE    |   N bytes payload  |
 * | length=N |                    |
 * +----------+--------------------+
 * ```
 *
 * The maximum payload size is bounded by [MAX_PAYLOAD_BYTES] — large
 * enough to fit any reasonable chat message, small enough to refuse a
 * trivially-malformed length header that would otherwise allocate
 * gigabytes on the heap before the malformed peer is detected.
 *
 * The codec is intentionally **not** Kotlin-flow / coroutine aware so
 * that:
 *  * the framing logic is trivially unit-testable on the host JVM;
 *  * `BluetoothSession` can drive it imperatively from its blocking
 *    read loop without paying the overhead of a per-byte SharedFlow.
 */
internal object MessageFraming {

    /**
     * Hard upper bound on a single payload, in bytes. Picked so that a
     * malicious peer cannot make us pre-allocate an unreasonable buffer
     * before we hang up the socket.
     */
    const val MAX_PAYLOAD_BYTES: Int = 1 shl 20 // 1 MiB

    /** Number of bytes in the big-endian length prefix. */
    const val LENGTH_PREFIX_BYTES: Int = 4

    /**
     * Wraps [payload] in the 4-byte big-endian length prefix used by
     * the BlueWave wire protocol.
     *
     * @throws IllegalArgumentException when [payload] is larger than
     *         [MAX_PAYLOAD_BYTES].
     */
    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload of ${payload.size} bytes exceeds the $MAX_PAYLOAD_BYTES-byte limit"
        }
        val buffer = ByteBuffer
            .allocate(LENGTH_PREFIX_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }
}

/**
 * Reassembler that turns an arbitrary stream of byte chunks into a
 * sequence of complete payloads, applying the
 * [MessageFraming.LENGTH_PREFIX_BYTES] header.
 *
 * The class is intentionally **not** thread-safe — callers are
 * expected to drive it from the single-threaded read loop owned by
 * [BluetoothSession].
 *
 * Internally we keep a growing [ByteArray] of bytes that have arrived
 * but not yet been emitted. Every time a full frame is available we
 * slice it out, advance the cursor, and continue until the buffer is
 * either empty or holds the start of an incomplete frame.
 */
internal class FrameAccumulator {

    private var buffer: ByteArray = EMPTY
    private var size: Int = 0

    /**
     * Feeds an additional [chunk] into the accumulator and returns
     * every full payload that has now become available, in arrival
     * order. Returns an empty list when the chunk only partially fills
     * the next frame.
     *
     * The buffer grows lazily (doubling) to avoid quadratic copying
     * when many small reads arrive between frame boundaries.
     *
     * @throws IllegalStateException when an inbound length header
     *         exceeds [MessageFraming.MAX_PAYLOAD_BYTES] — the caller
     *         should treat that as a fatal protocol error and tear
     *         down the session.
     */
    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val newSize = size + chunk.size
        if (newSize > MAX_BUFFER_SIZE) {
            throw IllegalStateException(
                "Accumulator buffer exceeded $MAX_BUFFER_SIZE bytes"
            )
        }
        ensureCapacity(newSize)
        System.arraycopy(chunk, 0, buffer, size, chunk.size)
        size = newSize
        return drainCompleteFrames()
    }

    private fun drainCompleteFrames(): List<ByteArray> {
        var cursor = 0
        var emitted: MutableList<ByteArray>? = null
        while (size - cursor >= MessageFraming.LENGTH_PREFIX_BYTES) {
            val payloadLength = readBigEndianInt(cursor)
            check(payloadLength in 0..MessageFraming.MAX_PAYLOAD_BYTES) {
                "Refusing to read frame of $payloadLength bytes (max ${MessageFraming.MAX_PAYLOAD_BYTES})"
            }
            val frameEnd = cursor + MessageFraming.LENGTH_PREFIX_BYTES + payloadLength
            if (frameEnd > size) break // wait for more bytes
            val payloadStart = cursor + MessageFraming.LENGTH_PREFIX_BYTES
            val payload = buffer.copyOfRange(payloadStart, frameEnd)
            (emitted ?: mutableListOf<ByteArray>().also { emitted = it }).add(payload)
            cursor = frameEnd
        }
        if (cursor > 0) {
            val remaining = size - cursor
            if (remaining > 0) {
                System.arraycopy(buffer, cursor, buffer, 0, remaining)
            }
            size = remaining
        }
        return emitted ?: emptyList()
    }

    private fun readBigEndianInt(offset: Int): Int {
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var newCapacity = if (buffer.isEmpty()) INITIAL_CAPACITY else buffer.size
        while (newCapacity < required) {
            val next = newCapacity * 2
            if (next < 0 || next > MAX_BUFFER_SIZE) {
                // Int overflow or exceeds hard cap — clamp
                newCapacity = MAX_BUFFER_SIZE.coerceAtLeast(required)
                break
            }
            newCapacity = next
        }
        val grown = ByteArray(newCapacity)
        if (size > 0) System.arraycopy(buffer, 0, grown, 0, size)
        buffer = grown
    }

    private companion object {
        val EMPTY: ByteArray = ByteArray(0)
        const val INITIAL_CAPACITY: Int = 1024
        /** Hard cap on the total bytes buffered across incomplete frames. */
        const val MAX_BUFFER_SIZE: Int = 1 shl 22 // 4 MiB
    }
}

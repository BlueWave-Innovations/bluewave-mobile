package com.example.bluewave_mobile.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [MessageFraming] and [FrameAccumulator].
 *
 * Verifies that the wire-protocol codec round-trips identical bytes,
 * tolerates fragmented reads (one frame split across many chunks) and
 * coalesced reads (several frames glued into one chunk), and refuses
 * frames larger than [MessageFraming.MAX_PAYLOAD_BYTES].
 */
class MessageFramingTest {

    @Test
    fun `frame and accumulator round-trip a single payload`() {
        val payload = "hello, peer".toByteArray(Charsets.UTF_8)
        val accumulator = FrameAccumulator()
        val emitted = accumulator.append(MessageFraming.frame(payload))
        assertEquals(1, emitted.size)
        assertArrayEquals(payload, emitted.single())
    }

    @Test
    fun `accumulator coalesces multiple frames in one chunk`() {
        val first = "first".toByteArray(Charsets.UTF_8)
        val second = "second message".toByteArray(Charsets.UTF_8)
        val third = ByteArray(0) // empty payload is legal
        val combined = MessageFraming.frame(first) +
            MessageFraming.frame(second) +
            MessageFraming.frame(third)

        val accumulator = FrameAccumulator()
        val emitted = accumulator.append(combined)
        assertEquals(3, emitted.size)
        assertArrayEquals(first, emitted[0])
        assertArrayEquals(second, emitted[1])
        assertArrayEquals(third, emitted[2])
    }

    @Test
    fun `accumulator splits a single frame across many chunks`() {
        val payload = ByteArray(2048) { (it and 0x7F).toByte() }
        val framed = MessageFraming.frame(payload)
        val accumulator = FrameAccumulator()

        // Drip-feed one byte at a time.
        for (i in 0 until framed.size - 1) {
            assertTrue(
                "frame must NOT be emitted before the last byte arrives",
                accumulator.append(byteArrayOf(framed[i])).isEmpty()
            )
        }
        val emitted = accumulator.append(byteArrayOf(framed.last()))
        assertEquals(1, emitted.size)
        assertArrayEquals(payload, emitted.single())
    }

    @Test
    fun `accumulator preserves leftover partial frame for the next chunk`() {
        val payload = "partial".toByteArray(Charsets.UTF_8)
        val framed = MessageFraming.frame(payload)
        val accumulator = FrameAccumulator()

        // First chunk: length prefix only -> nothing emitted yet.
        assertTrue(accumulator.append(framed.copyOfRange(0, MessageFraming.LENGTH_PREFIX_BYTES)).isEmpty())
        // Second chunk: payload arrives -> exactly one frame surfaces.
        val emitted = accumulator.append(framed.copyOfRange(MessageFraming.LENGTH_PREFIX_BYTES, framed.size))
        assertEquals(1, emitted.size)
        assertArrayEquals(payload, emitted.single())
    }

    @Test
    fun `accumulator rejects oversized frames as a protocol error`() {
        val accumulator = FrameAccumulator()
        // Hand-craft a header that claims (MAX + 1) bytes of payload.
        val oversized = MessageFraming.MAX_PAYLOAD_BYTES + 1
        val header = byteArrayOf(
            (oversized ushr 24 and 0xFF).toByte(),
            (oversized ushr 16 and 0xFF).toByte(),
            (oversized ushr 8 and 0xFF).toByte(),
            (oversized and 0xFF).toByte(),
        )
        assertThrows(IllegalStateException::class.java) {
            accumulator.append(header)
        }
    }

    @Test
    fun `frame refuses payloads larger than the limit`() {
        val tooBig = ByteArray(MessageFraming.MAX_PAYLOAD_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            MessageFraming.frame(tooBig)
        }
    }

    @Test
    fun `frame preserves an exactly-MAX-bytes payload`() {
        val maxPayload = ByteArray(MessageFraming.MAX_PAYLOAD_BYTES) { 0x42 }
        val framed = MessageFraming.frame(maxPayload)
        assertEquals(MessageFraming.LENGTH_PREFIX_BYTES + MessageFraming.MAX_PAYLOAD_BYTES, framed.size)
        val accumulator = FrameAccumulator()
        val emitted = accumulator.append(framed)
        assertEquals(1, emitted.size)
        assertArrayEquals(maxPayload, emitted.single())
    }
}

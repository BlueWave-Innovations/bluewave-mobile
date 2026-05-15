package com.example.bluewave_mobile.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the [BlueWaveFrame] codec.
 *
 * The codec is responsible for multiplexing E2EE-related frame
 * variants (`KEY_BUNDLE`, `SIGNAL_MESSAGE`, `PREKEY_SIGNAL_MESSAGE`)
 * over the same RFCOMM stream that already carries the
 * length-prefix wire framing. We verify three properties:
 *
 *  * **round-trip** — `decode(encode(t, p)) == Frame(t, p)` for every
 *    [BlueWaveFrame.Type] and a representative payload;
 *  * **forward compatibility** — an unknown tag byte returns `null`
 *    rather than throwing, so peers running newer code can introduce
 *    new frame kinds without crashing older ones;
 *  * **empty payload** — `decode` does not blow up on a frame with
 *    only the tag byte (used by future "ping" frames).
 *
 * No mocking, no coroutines: everything runs in a single thread.
 */
class BlueWaveFrameTest {

    @Test
    fun `every frame type round-trips through encode decode`() {
        val payload = ByteArray(128) { (it * 7 + 1).toByte() }
        for (type in BlueWaveFrame.Type.entries) {
            val encoded = BlueWaveFrame.encode(type, payload)
            assertEquals(payload.size + 1, encoded.size)
            assertEquals(type.tag, encoded[0])

            val decoded = BlueWaveFrame.decode(encoded)
            checkNotNull(decoded) { "decode returned null for $type" }
            assertEquals(type, decoded.type)
            assertArrayEquals(payload, decoded.payload)
        }
    }

    @Test
    fun `decode returns null for an empty frame`() {
        assertNull(BlueWaveFrame.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for an unknown tag byte`() {
        // 0xFF is intentionally outside every Type.tag value so this
        // also guards against future expansion accidentally collapsing
        // the namespace into the previously-reserved range.
        val unknown = byteArrayOf(0xFF.toByte(), 1, 2, 3)
        assertNull(BlueWaveFrame.decode(unknown))
    }

    @Test
    fun `decode preserves a zero-length payload`() {
        val tagOnly = byteArrayOf(BlueWaveFrame.Type.KEY_BUNDLE.tag)
        val decoded = checkNotNull(BlueWaveFrame.decode(tagOnly))
        assertEquals(BlueWaveFrame.Type.KEY_BUNDLE, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `Frame equality and hashCode are content-based`() {
        // The data class manually overrides equals/hashCode because of
        // the ByteArray field; verify the override behaves the way
        // every collection-based call site assumes.
        val a = BlueWaveFrame.Frame(BlueWaveFrame.Type.SIGNAL_MESSAGE, byteArrayOf(1, 2, 3))
        val b = BlueWaveFrame.Frame(BlueWaveFrame.Type.SIGNAL_MESSAGE, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val differentPayload = BlueWaveFrame.Frame(
            BlueWaveFrame.Type.SIGNAL_MESSAGE,
            byteArrayOf(9, 9, 9),
        )
        assertEquals(false, a == differentPayload)
    }
}

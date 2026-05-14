package com.example.bluewave_mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the [QrContactPayload] URI codec.
 *
 * The codec is what stitches the profile QR feature (encode side
 * fed into ZXing) and the QR scan flow (decode side fed by either
 * a camera scan or a clipboard paste) together. We verify three
 * properties:
 *
 *  * **round-trip** — `parse(toUri(p)) == p` for the canonical happy
 *    path with every optional field populated;
 *  * **defensive parsing** — malformed scheme / authority / MAC
 *    inputs return `null` instead of throwing;
 *  * **case insensitivity** — MAC addresses round-trip through the
 *    uppercased canonical form even when the user pasted a
 *    lowercase variant.
 *
 * Runs under the vanilla JUnit test runner because the codec does
 * not touch any `android.*` API — it uses [java.net.URLEncoder] /
 * [java.net.URLDecoder] for percent-encoding and a hand-rolled
 * scheme/authority parser for everything else.
 */
class QrContactPayloadTest {

    @Test
    fun `round trip preserves every populated field`() {
        val payload = QrContactPayload(
            macAddress = "AA:BB:CC:DD:EE:FF",
            displayName = "Алекс Иванов",
            handle = "alex_ivanov",
        )
        val uri = payload.toUri()
        val decoded = checkNotNull(QrContactPayload.parse(uri))
        assertEquals(payload.macAddress, decoded.macAddress)
        assertEquals(payload.displayName, decoded.displayName)
        assertEquals(payload.handle, decoded.handle)
    }

    @Test
    fun `parse upper-cases lowercase MAC addresses`() {
        val parsed = checkNotNull(
            QrContactPayload.parse("bluewave://contact?mac=aa:bb:cc:dd:ee:ff&name=A"),
        )
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.macAddress)
    }

    @Test
    fun `parse rejects a malformed MAC address`() {
        assertNull(QrContactPayload.parse("bluewave://contact?mac=not-a-mac"))
    }

    @Test
    fun `parse rejects an unknown scheme`() {
        assertNull(QrContactPayload.parse("https://contact?mac=AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `parse rejects an unknown authority`() {
        assertNull(
            QrContactPayload.parse("bluewave://other?mac=AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `parse returns null for an empty input`() {
        assertNull(QrContactPayload.parse(""))
    }

    @Test
    fun `optional fields are omitted when blank`() {
        val payload = QrContactPayload(
            macAddress = "AA:BB:CC:DD:EE:FF",
            displayName = "",
            handle = "",
        )
        val uri = payload.toUri()
        // The query string must contain a `mac=` parameter but
        // neither `name=` nor `tag=` so the encoded URI stays
        // compact for blank profiles. Note that URLEncoder escapes
        // ':' as '%3A' on the wire — that is fine because the
        // parser round-trips the percent-encoded form back to the
        // canonical MAC.
        assertEquals(true, uri.startsWith("bluewave://contact?mac="))
        assertEquals(false, uri.contains("name="))
        assertEquals(false, uri.contains("tag="))
        val decoded = checkNotNull(QrContactPayload.parse(uri))
        assertEquals("AA:BB:CC:DD:EE:FF", decoded.macAddress)
        assertEquals("", decoded.displayName)
        assertEquals("", decoded.handle)
    }

    @Test
    fun `parse preserves a non-blank handle round trip`() {
        val parsed = checkNotNull(
            QrContactPayload.parse(
                "bluewave://contact?mac=AA:BB:CC:DD:EE:FF&tag=alex",
            ),
        )
        assertEquals("alex", parsed.handle)
        assertNotNull(parsed.macAddress)
    }
}

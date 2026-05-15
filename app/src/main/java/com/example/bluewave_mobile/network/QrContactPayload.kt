package com.example.bluewave_mobile.network

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Wire-level shape of the data we encode inside a profile QR code.
 *
 * The QR is rendered as a [`bluewave://contact`](Companion.SCHEME)
 * URI rather than an opaque blob so a peer who scans it with the
 * platform camera ("Open in browser" / clipboard handlers) still
 * gets a sensible deep link they can paste back into BlueWave.
 *
 *  * **mac** — uppercased, colon-separated MAC of the local device.
 *    The receiving device feeds it straight into
 *    [com.example.bluewave_mobile.network.BluetoothDiscovery] so it
 *    can resolve the peer in the next discovery cycle.
 *  * **displayName** — friendly label rendered in the add-contact
 *    confirmation. Optional; the peer will fall back to the device
 *    name from the SDP record if missing.
 *  * **handle** — Telegram-style "@user" tag, kept lowercase and
 *    `@`-prefixed by the encoder for parity with `LocalProfile`.
 *    Optional.
 *
 * Identity-key fingerprints are intentionally *not* included — the
 * libsignal handshake on first connection is the source of truth for
 * cryptographic identity. Embedding a fingerprint here would only
 * give the user a way to second-guess the handshake without actually
 * authenticating it.
 *
 * Implementation note — the codec deliberately uses the JVM's
 * [URLEncoder] / [URLDecoder] (UTF-8) instead of `android.net.Uri`
 * so it can be exercised under the vanilla JUnit test runner
 * without pulling in Robolectric just for this codec.
 */
data class QrContactPayload(
    val macAddress: String,
    val displayName: String,
    val handle: String,
) {
    /**
     * Render this payload as a `bluewave://contact?...` deep link.
     * Empty fields are omitted so the resulting URI stays compact and
     * decoders that only care about the MAC do not need to special-case
     * placeholder values.
     */
    fun toUri(): String {
        val params: MutableList<Pair<String, String>> = mutableListOf(
            PARAM_MAC to macAddress.uppercase(),
        )
        if (displayName.isNotBlank()) {
            params += PARAM_NAME to displayName.trim()
        }
        if (handle.isNotBlank()) {
            params += PARAM_HANDLE to handle.trim()
        }
        // URLEncoder escapes ':' as '%3A' (technically legal but
        // ugly inside a MAC). Round-tripping through URLDecoder
        // restores the canonical bytes on the other side, so we
        // accept the cosmetic noise to keep the encoder minimal.
        val query = params.joinToString(separator = "&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        return "$SCHEME://$AUTHORITY?$query"
    }

    companion object {
        /** URI scheme used by every BlueWave deep link. */
        const val SCHEME: String = "bluewave"

        /** Authority component identifying the contact-add intent. */
        const val AUTHORITY: String = "contact"

        private const val PARAM_MAC = "mac"
        private const val PARAM_NAME = "name"
        private const val PARAM_HANDLE = "tag"

        /** MAC pattern used to validate the parsed `mac` parameter. */
        private val MAC_PATTERN: Regex =
            Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /**
         * Parse a `bluewave://contact?...` URI into a structured
         * payload. Returns `null` for any URI that:
         *  * does not match the [SCHEME] / [AUTHORITY] tuple,
         *  * is missing the required `mac` parameter,
         *  * carries a `mac` parameter that fails [MAC_PATTERN].
         *
         * The caller surfaces a localized error from the returned
         * `null` rather than throwing, so a malformed paste does not
         * crash the screen.
         */
        fun parse(input: String): QrContactPayload? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val schemeSplit = trimmed.indexOf("://")
            if (schemeSplit <= 0) return null
            val scheme = trimmed.substring(0, schemeSplit)
            if (!scheme.equals(SCHEME, ignoreCase = true)) return null
            val rest = trimmed.substring(schemeSplit + 3)
            val querySplit = rest.indexOf('?')
            val authorityPart = if (querySplit < 0) rest else rest.substring(0, querySplit)
            val queryPart = if (querySplit < 0) "" else rest.substring(querySplit + 1)
            // Strip any trailing path / fragment so callers can
            // paste a URL ending in `/` or with a `#fragment` and
            // still get a valid match.
            val authority = authorityPart.substringBefore('/').substringBefore('#')
            if (!AUTHORITY.equals(authority, ignoreCase = true)) return null
            val params = parseQuery(queryPart.substringBefore('#'))
            val mac = params[PARAM_MAC]?.uppercase()?.takeUnless(String::isBlank) ?: return null
            if (!MAC_PATTERN.matches(mac)) return null
            val name = params[PARAM_NAME].orEmpty()
            val handle = params[PARAM_HANDLE].orEmpty()
            return QrContactPayload(
                macAddress = mac,
                displayName = name,
                handle = handle,
            )
        }

        /**
         * Parse the `key1=val1&key2=val2` portion of a URL.
         *
         * Uses [URLDecoder] so percent-encoded UTF-8 round-trips
         * cleanly — necessary for non-ASCII display names like
         * "Алекс Иванов". First occurrence wins so `?mac=A&mac=B`
         * does not silently swap the canonical value out from
         * under the caller.
         */
        private fun parseQuery(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val out: MutableMap<String, String> = LinkedHashMap()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val rawKey = pair.substring(0, eq)
                val rawValue = pair.substring(eq + 1)
                val key = runCatching {
                    URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
                }.getOrNull() ?: continue
                val value = runCatching {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
                }.getOrNull() ?: continue
                out.putIfAbsent(key, value)
            }
            return out
        }
    }
}

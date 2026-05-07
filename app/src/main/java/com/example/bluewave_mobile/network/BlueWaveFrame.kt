package com.example.bluewave_mobile.network

/**
 * Wire-level envelope for every payload that crosses an RFCOMM
 * session between two BlueWave peers.
 *
 * The transport layer (`BluetoothSession`) keeps its
 * length-prefix framing untouched — that codec is responsible only
 * for splitting the byte stream into discrete frames. *Inside* each
 * frame, BlueWave needs to distinguish multiple payload kinds:
 *
 *  * `KEY_BUNDLE` (0x01) — a serialized libsignal `PreKeyBundle`
 *    sent at handshake time so the peer can build a Signal session
 *    against us;
 *  * `SIGNAL_MESSAGE` (0x02) — a regular libsignal `SignalMessage`
 *    that the receiver decrypts via `SessionCipher`;
 *  * `PREKEY_SIGNAL_MESSAGE` (0x03) — the very first encrypted
 *    message after a fresh handshake; carries enough information
 *    inside the libsignal envelope to bootstrap a session on the
 *    other side as well.
 *
 * A single byte tag is intentionally minimal: every frame already
 * paid the length prefix overhead, so anything more than one byte
 * of type metadata would be wasted on chat-sized payloads.
 *
 * The codec in this file is symmetric — [encode] takes a [Type]
 * plus a payload and returns the bytes that ship over the wire,
 * [decode] turns those bytes back into a [Frame]. Round-tripping
 * every supported [Type] is asserted in the unit-test layer.
 */
object BlueWaveFrame {

    /** Wire tag for a frame body. */
    enum class Type(val tag: Byte) {
        KEY_BUNDLE(0x01),
        SIGNAL_MESSAGE(0x02),
        PREKEY_SIGNAL_MESSAGE(0x03),
        ;

        companion object {
            /**
             * Parses a tag byte back into the matching [Type], or `null`
             * if it does not correspond to any known frame kind. The
             * call site is expected to drop the frame on `null` —
             * forward-compatibility means newer peers can introduce
             * new tags without crashing older ones.
             */
            fun fromTag(tag: Byte): Type? = entries.firstOrNull { it.tag == tag }
        }
    }

    /**
     * Decoded view of a wire frame. The [payload] reference is
     * shared with the underlying byte array — callers MUST NOT
     * mutate it (every transport-layer buffer is re-used).
     */
    data class Frame(val type: Type, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
    }

    /**
     * Concatenates the [type] tag with the [payload] body. The
     * result is what the transport hands to the length-prefix codec.
     */
    fun encode(type: Type, payload: ByteArray): ByteArray {
        val frame = ByteArray(payload.size + 1)
        frame[0] = type.tag
        System.arraycopy(payload, 0, frame, 1, payload.size)
        return frame
    }

    /**
     * Splits an inbound frame into its [Type] tag and body.
     *
     * Returns `null` when:
     *  * the frame is empty (no tag byte);
     *  * the tag does not correspond to any known [Type] (forward
     *    compat — see [Type.fromTag]).
     */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.isEmpty()) return null
        val type = Type.fromTag(bytes[0]) ?: return null
        val body = ByteArray(bytes.size - 1)
        System.arraycopy(bytes, 1, body, 0, body.size)
        return Frame(type, body)
    }
}

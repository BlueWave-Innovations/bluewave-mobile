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
 *    other side as well;
 *  * `PROFILE_METADATA` (0x04) — encrypted profile-card update
 *    (display name / @handle / bio / avatar URI) pushed by the
 *    peer once the Signal session is `SECURE`. The body is
 *    `[1B subtype][libsignal ciphertext]` where the subtype byte
 *    is `0x02` for `SignalMessage` and `0x03` for
 *    `PreKeySignalMessage` — same encoding as the corresponding
 *    standalone `Type` tags so the inner payload is symmetric
 *    with the regular text-message path.
 *  * `GROUP_INVITE` (0x05) — encrypted multi-peer group bootstrap
 *    pushed by the group's owner to every prospective member when
 *    the group is first created. The body re-uses the
 *    `[1B subtype][libsignal ciphertext]` envelope — when
 *    decrypted, the inner plaintext is the JSON-encoded
 *    `GroupInvitePayload`.
 *  * `GROUP_MESSAGE` (0x06) — encrypted multi-peer group text. Same
 *    envelope as `GROUP_INVITE`. The decrypted plaintext is
 *    `[2B groupIdLen big-endian][groupId UTF-8][message UTF-8]`.
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
        PROFILE_METADATA(0x04),
        GROUP_INVITE(0x05),
        GROUP_MESSAGE(0x06),
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

    /**
     * Inner codec for the body of a [Type.PROFILE_METADATA] frame.
     *
     * The body is `[1B subtype tag][libsignal ciphertext]` where the
     * subtype tag re-uses [Type.SIGNAL_MESSAGE] (0x02) and
     * [Type.PREKEY_SIGNAL_MESSAGE] (0x03) — exactly the two
     * top-level frame kinds that the regular text-message path uses.
     *
     * Splitting the codec out keeps `MessageRepositoryImpl` free of
     * byte-fiddling and lets the unit tests round-trip the inner
     * envelope independently of the libsignal wire format.
     */
    object ProfileEnvelope {

        /**
         * Subtypes a [Type.PROFILE_METADATA] body can hold. They
         * mirror the corresponding top-level frame tags so the wire
         * format stays self-explanatory when read out of a packet
         * dump.
         */
        enum class Subtype(val tag: Byte) {
            SIGNAL_MESSAGE(Type.SIGNAL_MESSAGE.tag),
            PREKEY_SIGNAL_MESSAGE(Type.PREKEY_SIGNAL_MESSAGE.tag),
            ;

            companion object {
                fun fromTag(tag: Byte): Subtype? = entries.firstOrNull { it.tag == tag }
            }
        }

        /** Decoded view of a [Type.PROFILE_METADATA] body. */
        data class Inner(val subtype: Subtype, val ciphertext: ByteArray) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Inner) return false
                return subtype == other.subtype && ciphertext.contentEquals(other.ciphertext)
            }

            override fun hashCode(): Int = 31 * subtype.hashCode() + ciphertext.contentHashCode()
        }

        /** Encode `[subtype][ciphertext]`. */
        fun encode(subtype: Subtype, ciphertext: ByteArray): ByteArray {
            val out = ByteArray(ciphertext.size + 1)
            out[0] = subtype.tag
            System.arraycopy(ciphertext, 0, out, 1, ciphertext.size)
            return out
        }

        /**
         * Decode `[subtype][ciphertext]`. Returns `null` for empty
         * bodies and for unknown subtype tags — the caller is
         * expected to drop the frame in that case.
         */
        fun decode(body: ByteArray): Inner? {
            if (body.isEmpty()) return null
            val subtype = Subtype.fromTag(body[0]) ?: return null
            val ciphertext = ByteArray(body.size - 1)
            System.arraycopy(body, 1, ciphertext, 0, ciphertext.size)
            return Inner(subtype, ciphertext)
        }
    }

    /**
     * Inner codec for the body of a [Type.GROUP_INVITE] or
     * [Type.GROUP_MESSAGE] frame.
     *
     * The encoding is identical to [ProfileEnvelope]'s — a single
     * subtype byte (0x02 for `SignalMessage`, 0x03 for
     * `PreKeySignalMessage`) followed by the libsignal ciphertext.
     * It is split into a separate object so the call sites in
     * `GroupRepository` read naturally and so the unit tests can
     * pin every group-related round-trip independently.
     */
    object GroupEnvelope {

        /** Subtypes a group frame body can hold. */
        enum class Subtype(val tag: Byte) {
            SIGNAL_MESSAGE(Type.SIGNAL_MESSAGE.tag),
            PREKEY_SIGNAL_MESSAGE(Type.PREKEY_SIGNAL_MESSAGE.tag),
            ;

            companion object {
                fun fromTag(tag: Byte): Subtype? = entries.firstOrNull { it.tag == tag }
            }
        }

        /** Decoded view of a group frame body. */
        data class Inner(val subtype: Subtype, val ciphertext: ByteArray) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Inner) return false
                return subtype == other.subtype && ciphertext.contentEquals(other.ciphertext)
            }

            override fun hashCode(): Int = 31 * subtype.hashCode() + ciphertext.contentHashCode()
        }

        /** Encode `[subtype][ciphertext]`. */
        fun encode(subtype: Subtype, ciphertext: ByteArray): ByteArray {
            val out = ByteArray(ciphertext.size + 1)
            out[0] = subtype.tag
            System.arraycopy(ciphertext, 0, out, 1, ciphertext.size)
            return out
        }

        /**
         * Decode `[subtype][ciphertext]`. Returns `null` for empty
         * bodies and for unknown subtype tags.
         */
        fun decode(body: ByteArray): Inner? {
            if (body.isEmpty()) return null
            val subtype = Subtype.fromTag(body[0]) ?: return null
            val ciphertext = ByteArray(body.size - 1)
            System.arraycopy(body, 1, ciphertext, 0, ciphertext.size)
            return Inner(subtype, ciphertext)
        }
    }

    /**
     * Inner codec for the *plaintext* body of a [Type.GROUP_MESSAGE]
     * frame, sitting one layer below [GroupEnvelope] (which handles
     * the libsignal wrap).
     *
     * Encoding: `[2B groupIdLen big-endian][groupId UTF-8 bytes]
     * [message UTF-8 bytes]`. Splitting `groupId` from the message
     * body in plaintext (post-libsignal) lets the receiver route the
     * payload to the right `group_message` row without paying for an
     * inner JSON parse on every chat message — only invites carry
     * structured JSON.
     *
     * `groupId` length is bounded by 64 KiB because the prefix is a
     * `UInt16`. All BlueWave group ids are UUID strings (36 ASCII
     * bytes) so this is a generous ceiling.
     */
    object GroupMessageBody {

        /** Decoded view of a `GROUP_MESSAGE` plaintext body. */
        data class Inner(val groupId: String, val message: ByteArray) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Inner) return false
                return groupId == other.groupId && message.contentEquals(other.message)
            }

            override fun hashCode(): Int = 31 * groupId.hashCode() + message.contentHashCode()
        }

        /** Encode `[2B groupIdLen][groupId][message]`. */
        fun encode(groupId: String, message: ByteArray): ByteArray {
            val groupIdBytes = groupId.toByteArray(Charsets.UTF_8)
            require(groupIdBytes.size <= UShort.MAX_VALUE.toInt()) {
                "groupId too long: ${groupIdBytes.size} bytes"
            }
            val out = ByteArray(2 + groupIdBytes.size + message.size)
            out[0] = ((groupIdBytes.size ushr 8) and 0xFF).toByte()
            out[1] = (groupIdBytes.size and 0xFF).toByte()
            System.arraycopy(groupIdBytes, 0, out, 2, groupIdBytes.size)
            System.arraycopy(message, 0, out, 2 + groupIdBytes.size, message.size)
            return out
        }

        /**
         * Decode `[2B groupIdLen][groupId][message]`. Returns `null`
         * when the body is shorter than the advertised length — the
         * caller is expected to drop the frame.
         */
        fun decode(body: ByteArray): Inner? {
            if (body.size < 2) return null
            val groupIdLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            if (body.size < 2 + groupIdLen) return null
            val groupIdBytes = ByteArray(groupIdLen)
            System.arraycopy(body, 2, groupIdBytes, 0, groupIdLen)
            val message = ByteArray(body.size - 2 - groupIdLen)
            System.arraycopy(body, 2 + groupIdLen, message, 0, message.size)
            return Inner(
                groupId = String(groupIdBytes, Charsets.UTF_8),
                message = message,
            )
        }
    }
}

package com.example.bluewave_mobile.network

import kotlinx.coroutines.flow.Flow

/**
 * Per-frame payload received from a remote peer.
 *
 * The [payload] is the raw bytes after [FrameAccumulator] has stripped
 * the length prefix — the data layer is responsible for any further
 * interpretation (UTF-8 decoding, encryption-at-rest, etc.).
 */
data class IncomingPeerMessage(
    val macAddress: String,
    val deviceName: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingPeerMessage) return false
        if (macAddress != other.macAddress) return false
        if (deviceName != other.deviceName) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = macAddress.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Abstraction over the radio transport that delivers messages between
 * peers.
 *
 * Keeping the transport behind an interface lets the data layer
 * (`MessageRepositoryImpl`) stay unaware of `android.bluetooth` and
 * makes the JVM unit tests trivially injectable — the real
 * implementation is [BluetoothSessionManager], a fake one is used in
 * tests.
 */
interface MessageTransport {

    /**
     * Cold stream of payloads as they arrive from any connected peer.
     * Subscribers are expected to be lifecycle-scoped (typically
     * `BlueWaveApplication.applicationScope`) so the transport can
     * stay alive across activity recreations.
     */
    val incoming: Flow<IncomingPeerMessage>

    /**
     * Cold stream of MAC addresses for which a fresh RFCOMM session
     * has just been attached. The repository subscribes to this flow
     * to fire its E2EE handshake (push the local libsignal key
     * bundle) the moment the link is up — clients of this interface
     * never observe the underlying socket lifecycle directly.
     */
    val sessionAttached: Flow<String>

    /**
     * Initiates an outgoing connection to [macAddress] if no live
     * session exists for that peer yet. Idempotent: calling it on a
     * peer that is already connected is a no-op.
     */
    suspend fun connect(macAddress: String)

    /**
     * Sends [payload] to the peer identified by [macAddress]. Returns
     * `true` when the bytes were handed to the underlying socket,
     * `false` when no live session exists for that peer or the write
     * itself failed (the transport will tear the session down in that
     * case).
     */
    suspend fun send(macAddress: String, payload: ByteArray): Boolean

    /**
     * Tears down the session for [macAddress] (if any) and releases
     * its resources. Safe to call for an unknown peer.
     */
    fun disconnect(macAddress: String)
}

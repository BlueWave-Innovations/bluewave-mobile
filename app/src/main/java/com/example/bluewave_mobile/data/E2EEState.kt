package com.example.bluewave_mobile.data

/**
 * Per-peer end-to-end encryption posture, surfaced to the UI through
 * [MessageRepository.observeSessionState].
 *
 * The chat screen renders a small lock indicator next to the peer's
 * name driven by this enum. Three states are enough to communicate
 * everything the user needs to know:
 *
 *  * [PENDING] — RFCOMM session is up but the libsignal X3DH
 *    handshake has not yet completed for this peer; messages still
 *    queue on the local DB and ship out as soon as the handshake
 *    finishes;
 *  * [SECURE] — a libsignal session exists for the peer, every
 *    outgoing frame is encrypted under the Double Ratchet and
 *    forward-secret;
 *  * [FAILED] — the most recent decrypt or handshake step threw a
 *    [com.example.bluewave_mobile.crypto.SignalEngineException]; the
 *    repository keeps trying on subsequent frames so this state is
 *    not necessarily terminal — it just signals that the peer's
 *    last attempt did not produce a valid session.
 */
enum class E2EEState {
    PENDING,
    SECURE,
    FAILED,
}

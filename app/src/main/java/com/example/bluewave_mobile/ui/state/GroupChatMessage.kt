package com.example.bluewave_mobile.ui.state

/**
 * Pre-decrypted, render-ready view of a single message inside a
 * multi-peer [com.example.bluewave_mobile.data.ChatGroupEntity].
 *
 * Mirrors [ChatMessage] but carries an extra [senderName] / [senderMac]
 * pair so the screen can prefix each incoming bubble with the sender's
 * display name (the Telegram-style "Алекс: привет" format) without an
 * extra DB join at render time.
 *
 * Outgoing rows still set [isOutgoing] = `true`; the sender fields are
 * populated for symmetry (so the same data class can drive a
 * "you" affordance in the future) but the screen does not currently
 * render them on outgoing bubbles.
 *
 * @property id Stable Room row id used as the LazyColumn key.
 * @property text Decrypted plaintext to render. Empty when [isCorrupted]
 *                is `true`.
 * @property senderMac Uppercased MAC of the device that authored the
 *                     message. Equals the local MAC for outgoing
 *                     messages.
 * @property senderName Display name captured at send time. Used as the
 *                      bubble prefix on inbound messages.
 * @property isOutgoing `true` if the local device authored the message.
 * @property timestamp Unix epoch milliseconds; formatted at render time.
 * @property isCorrupted `true` when the GCM tag rejected the
 *                       at-rest ciphertext — see
 *                       [com.example.bluewave_mobile.crypto.DecryptionResult.Tampered].
 */
data class GroupChatMessage(
    val id: Long,
    val text: String,
    val senderMac: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val isCorrupted: Boolean = false,
)

package com.example.bluewave_mobile.ui.state

/**
 * Pre-decrypted, render-ready view of a single chat row.
 *
 * The data layer ([com.example.bluewave_mobile.data.MessageEntity]) holds
 * the encrypted payload and IV; the chat presentation layer is
 * responsible for decrypting via
 * [com.example.bluewave_mobile.crypto.CryptoManager.decrypt] and
 * mapping the result to one of the cases below. Keeping this UI model
 * decoupled from the database entity lets the LazyColumn diff stable
 * items without leaking ByteArray identity into the recomposition
 * graph, and lets the decryption work be hoisted off the main thread
 * inside `ChatViewModel`.
 *
 * The class is intentionally Compose-free so it can be unit-tested
 * with `kotlinx.coroutines.test` (planned for step 40) without
 * pulling in the Android runtime.
 *
 * @property id Stable Room row id used as the LazyColumn key.
 * @property text Decrypted plaintext to render. Empty when [isCorrupted] is true.
 * @property isOutgoing True if this device authored the message.
 * @property timestamp Unix epoch ms; formatted at render time.
 * @property isCorrupted True if the GCM tag rejected the ciphertext —
 *                       see [com.example.bluewave_mobile.crypto.DecryptionResult.Tampered].
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val isOutgoing: Boolean,
    val timestamp: Long,
    val isCorrupted: Boolean = false,
    val deliveryStatus: Int = 0,
)

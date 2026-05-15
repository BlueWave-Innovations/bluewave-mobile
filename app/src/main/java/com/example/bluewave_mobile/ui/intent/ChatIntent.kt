package com.example.bluewave_mobile.ui.intent

/**
 * Exhaustive list of user-driven side effects the chat screen can
 * dispatch towards its `ChatViewModel`.
 *
 * MVI calls these "intents". A composable never invokes a method on
 * the ViewModel directly — it sends an intent through a single
 * `(ChatIntent) -> Unit` lambda, which lets us:
 *
 *  * keep the `@Composable` signature stable when new intents are
 *    added (no extra constructor arguments to thread through);
 *  * record + replay user actions in instrumentation tests
 *    (`flowOf(SendMessage("Hi"), Retry, …)`);
 *  * unit-test the reducer in `kotlinx.coroutines.test` without
 *    referencing any Android type.
 *
 * Each branch carries exactly the data needed to execute the action —
 * no shared mutable state.
 */
sealed interface ChatIntent {
    /**
     * Submit [plaintext] to the encrypt → persist → transmit pipeline
     * defined in `MessageRepository.sendMessage`.
     */
    data class SendMessage(val plaintext: String) : ChatIntent

    /** User pulled-to-refresh / retried after an `Error` state. */
    data object Retry : ChatIntent

    /** User cleared the entire conversation for the active peer. */
    data object ClearHistory : ChatIntent

    /**
     * Submit a media file to the encrypt → persist → transmit pipeline.
     * [localPath] must be an absolute path in app-private storage where
     * the file has already been copied by the UI layer.
     */
    data class SendMedia(
        val attachmentName: String,
        val mimeType: String,
        val localPath: String,
    ) : ChatIntent

    /** Cancels an in-flight outgoing media upload and deletes the row. */
    data class CancelSend(val messageId: Long) : ChatIntent
}

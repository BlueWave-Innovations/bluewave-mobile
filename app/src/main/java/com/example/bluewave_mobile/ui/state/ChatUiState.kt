package com.example.bluewave_mobile.ui.state

import com.example.bluewave_mobile.data.E2EEState
import com.example.bluewave_mobile.data.MessageEntity

/**
 * Exhaustive description of every screen state the chat UI can be in.
 *
 * The MVI pattern (Model–View–Intent) requires the ViewModel to expose
 * a single `StateFlow<ChatUiState>` that the composable observes. Each
 * branch of this `sealed interface` is a complete, mutually-exclusive
 * snapshot the UI can render — there is no "loading flag" sprayed
 * across other states because the compiler already enforces
 * exhaustiveness through `when (state) { … }`.
 *
 *  * [Loading] — initial state before the first DB read completes.
 *  * [Success] — Room flow has emitted at least once. Carries the full
 *    ordered list of messages for the active peer.
 *  * [PeerOffline] — observed an Android-16
 *    `ACTION_KEY_MISSING` for this peer; outgoing messages are paused
 *    by the data layer (see `MessageRepository.pauseNetworkOperations`).
 *  * [Error] — fatal terminal state used when the backing flow fails
 *    in a non-recoverable way (DB corruption, decryption fault, etc.).
 *
 * Keep this file free of Compose / Android imports — the contract must
 * stay testable in pure Kotlin / JVM `kotlinx.coroutines.test` runs
 * planned for step 40.
 */
sealed interface ChatUiState {
    /** No data has been loaded yet. */
    data object Loading : ChatUiState

    /**
     * Live snapshot of the chat history.
     *
     * @property messages All messages for the active peer, ordered
     *                    chronologically (`ASC` — see [com.example.bluewave_mobile.data.MessageDao.getMessagesByDevice]).
     * @property isPeerPaused `true` while the peer is in the
     *                        ACTION_KEY_MISSING state — the input row
     *                        should disable the send button instead of
     *                        silently dropping outgoing messages.
     */
    data class Success(
        val messages: List<MessageEntity>,
        val isPeerPaused: Boolean = false,
        val e2eeState: E2EEState = E2EEState.PENDING,
    ) : ChatUiState

    /**
     * The peer's bond keys went missing on Android 16; outgoing
     * messages are queued client-side until [com.example.bluewave_mobile.data.MessageRepository.resumeNetworkOperations]
     * is called by the BroadcastReceiver listening for
     * `ACTION_ENCRYPTION_CHANGE`.
     */
    data class PeerOffline(val macAddress: String) : ChatUiState

    /** Terminal failure — usually rendered with a retry CTA. */
    data class Error(val message: String) : ChatUiState
}

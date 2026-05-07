package com.example.bluewave_mobile.ui.model

/**
 * One row in the sectioned device-list / contacts screen.
 *
 * The list is rendered top-to-bottom in three sections derived from the
 * subtype:
 *
 *  1. **Chats** — [ExistingChat]: peers we have already exchanged
 *     messages with. Sorted by last-message timestamp, newest first.
 *  2. **Can start chat** — [StartChatCandidate]: peers visible on the
 *     radio whose SDP record contains the BlueWave service UUID, but
 *     for whom we have no persisted history yet.
 *  3. **No app yet** — [InstallSuggestion]: peers visible on the radio
 *     whose SDP record does **not** advertise the BlueWave service.
 *     Tapping the row's CTA hands the staged APK to the system
 *     Bluetooth share dialog so the user can offer it for install.
 *
 * Keeping the three states as a sealed interface lets the screen
 * filter / branch on type without a parallel "kind" enum and gives
 * Compose `LazyColumn` a stable diff key (`displayName + macAddress`)
 * regardless of which section a row currently sits in.
 */
sealed interface ContactRow {

    /** Friendly device name used as the primary line of the row. */
    val displayName: String

    /** Uppercase MAC address; doubles as the navigation argument. */
    val macAddress: String

    /**
     * Existing one-on-one conversation with at least one persisted
     * message. Drives the "Chats" section.
     *
     * @param lastMessageTimestamp Unix epoch milliseconds of the most
     *                              recent persisted message, used for
     *                              the right-aligned timestamp label.
     * @param unreadCount Number of inbound messages that have not yet
     *                    been opened by the local user. `0` hides the
     *                    badge in the row composable.
     * @param isOnline `true` while [com.example.bluewave_mobile.network.MessageTransport]
     *                 holds a live RFCOMM session to this peer.
     */
    data class ExistingChat(
        override val displayName: String,
        override val macAddress: String,
        val lastMessagePreview: String,
        val lastMessageTimestamp: Long,
        val unreadCount: Int,
        val isOnline: Boolean,
    ) : ContactRow

    /**
     * Peer found by classic discovery whose SDP record contains the
     * BlueWave service UUID but for whom no chat history exists yet.
     */
    data class StartChatCandidate(
        override val displayName: String,
        override val macAddress: String,
        val isBonded: Boolean,
    ) : ContactRow

    /**
     * Peer found by classic discovery without the BlueWave service
     * UUID in its SDP record — tapping the row's primary action
     * triggers the system Bluetooth share dialog with the staged APK.
     */
    data class InstallSuggestion(
        override val displayName: String,
        override val macAddress: String,
    ) : ContactRow
}

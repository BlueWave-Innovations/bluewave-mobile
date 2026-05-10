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

    /**
     * Multi-peer group conversation rendered alongside one-on-one
     * chats in the "Chats" section.
     *
     * Reuses [macAddress] as the carrier of the group's stable
     * opaque id so the row can flow through the same [LazyColumn]
     * keying as the other [ContactRow] subtypes — the screen
     * branches on the runtime subtype to navigate to
     * [com.example.bluewave_mobile.ui.navigation.GroupChatRoute]
     * instead of [com.example.bluewave_mobile.ui.navigation.ChatRoute].
     *
     * @param groupId Stable identifier minted by [com.example.bluewave_mobile.data.GroupRepository.createGroup].
     * @param memberCount Total number of peers in the group, including
     *                     the local device.
     * @param lastMessagePreview Plaintext preview of the latest
     *                            message — empty when the group
     *                            history is empty.
     * @param lastMessageTimestamp Unix epoch milliseconds of the
     *                              latest message, used as a sort
     *                              key alongside [ExistingChat].
     * @param unreadCount Number of inbound group messages that have
     *                     not yet been opened.
     */
    data class GroupChat(
        override val displayName: String,
        val groupId: String,
        val memberCount: Int,
        val lastMessagePreview: String,
        val lastMessageTimestamp: Long,
        val unreadCount: Int,
    ) : ContactRow {
        override val macAddress: String get() = groupId
    }
}

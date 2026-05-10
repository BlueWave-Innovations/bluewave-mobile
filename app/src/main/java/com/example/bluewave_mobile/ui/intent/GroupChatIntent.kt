package com.example.bluewave_mobile.ui.intent

/**
 * MVI intent set for the group-chat screen.
 *
 * Mirrors [ChatIntent] but routes through
 * [com.example.bluewave_mobile.data.GroupRepository] for fan-out
 * encryption rather than a single pairwise [com.example.bluewave_mobile.data.MessageRepository].
 */
sealed interface GroupChatIntent {

    /** User tapped Send with [plaintext] in the compose bar. */
    data class SendMessage(val plaintext: String) : GroupChatIntent

    /** User tapped the retry CTA after a load / decrypt failure. */
    data object Retry : GroupChatIntent
}

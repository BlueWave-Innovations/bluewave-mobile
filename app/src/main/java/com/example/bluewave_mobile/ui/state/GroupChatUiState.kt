package com.example.bluewave_mobile.ui.state

import com.example.bluewave_mobile.data.ChatGroupEntity
import com.example.bluewave_mobile.data.GroupMemberEntity

/**
 * MVI screen state for the group chat surface.
 *
 * Mirrors [ChatUiState] but is keyed off the group's stable opaque
 * id rather than a peer MAC address. The screen renders the
 * loading / error / success branches directly; success carries the
 * decrypted message list, the group metadata, and the membership
 * roster so the top bar can render "%d members" without a
 * secondary join.
 */
sealed interface GroupChatUiState {

    /** No DB emission has reached the ViewModel yet. */
    data object Loading : GroupChatUiState

    /**
     * The repository failed to load or decrypt the group history;
     * the screen surfaces a retry CTA wired to [com.example.bluewave_mobile.ui.intent.GroupChatIntent.Retry].
     */
    data class Error(val message: String) : GroupChatUiState

    /**
     * The group exists and the message list (possibly empty) has
     * been decrypted. [group] / [members] drive the top bar, while
     * [messages] drive the LazyColumn body.
     */
    data class Success(
        val group: ChatGroupEntity,
        val members: List<GroupMemberEntity>,
        val messages: List<GroupChatMessage>,
    ) : GroupChatUiState
}

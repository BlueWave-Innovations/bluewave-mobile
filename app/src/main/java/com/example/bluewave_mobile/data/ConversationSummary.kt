package com.example.bluewave_mobile.data

/**
 * Lightweight per-peer roll-up surfaced by
 * [MessageRepository.observeAllConversations].
 *
 * Carries everything the device-list "Chats" section needs to render
 * a row without subscribing to the full chat history per peer:
 *
 *  * [macAddress] — uppercase MAC of the remote, used as a stable list
 *    key and as the navigation argument for opening the chat;
 *  * [lastMessage] — the most recent persisted [MessageEntity] for the
 *    peer; the encrypted payload is left intact so the UI layer
 *    decides whether to spend CPU on a preview decrypt;
 *  * [unreadCount] — number of inbound messages with `isRead = false`,
 *    drives the unread badge; outgoing messages never contribute to
 *    this count by construction (they are inserted with
 *    `isRead = true`).
 */
data class ConversationSummary(
    val macAddress: String,
    val lastMessage: MessageEntity,
    val unreadCount: Int,
)

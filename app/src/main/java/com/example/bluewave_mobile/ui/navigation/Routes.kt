package com.example.bluewave_mobile.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Root tab — sectioned chats list (existing chats / can write /
 * no app yet) plus the discovery scan trigger.
 */
@Serializable
object DeviceListRoute

/**
 * Tab — settings: profile card, theme picker, language picker,
 * Bluetooth visibility, folders entry.
 */
@Serializable
object SettingsRoute

/**
 * Tab — local profile screen: avatar, name, @handle, bio, edit
 * actions, and "open my QR" CTA.
 */
@Serializable
object ProfileRoute

/**
 * Sub-screen — opened from the settings folders entry; lets the
 * user create / rename / delete folders and reassign peers.
 */
@Serializable
object FoldersManagementRoute

/**
 * Sub-screen — opened from the chats list FAB; member-picker that
 * lets the user create a new group from existing peers.
 */
@Serializable
object CreateGroupRoute

/**
 * Sub-screen — opened from a peer's chat header or the floating
 * "scan" CTA; renders the local profile QR and parses scanned
 * codes back into add-contact intents.
 */
@Serializable
object QrShareRoute

/**
 * One-to-one chat between the local user and a peer identified
 * by their RFCOMM-stable MAC.
 */
@Serializable
data class ChatRoute(val deviceMac: String)

/**
 * Group chat — `groupId` is the locally-issued UUID assigned by
 * [com.example.bluewave_mobile.data.GroupRepository.createGroup].
 */
@Serializable
data class GroupChatRoute(val groupId: String)

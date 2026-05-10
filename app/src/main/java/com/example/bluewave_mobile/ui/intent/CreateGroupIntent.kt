package com.example.bluewave_mobile.ui.intent

/**
 * MVI intent set for [com.example.bluewave_mobile.ui.screens.CreateGroupScreen].
 */
sealed interface CreateGroupIntent {

    /** User typed [name] into the group-name field. */
    data class UpdateName(val name: String) : CreateGroupIntent

    /**
     * User tapped the row for the peer with [macAddress]; the VM
     * flips the selection so the same row toggles between
     * "checked" and "unchecked".
     */
    data class ToggleMember(val macAddress: String) : CreateGroupIntent

    /** User tapped the "Create" CTA. */
    data object Submit : CreateGroupIntent
}

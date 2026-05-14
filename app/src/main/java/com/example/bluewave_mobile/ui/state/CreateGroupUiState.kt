package com.example.bluewave_mobile.ui.state

/**
 * Pre-rendered description of one row in the create-group member
 * picker.
 *
 * The screen renders the picker as a multi-select list — each row
 * shows the peer's display name and a checkbox that toggles
 * [selected]. The view model derives the list by combining live
 * conversation summaries (peers we've already chatted with) with the
 * radio-side bonded list so the user can target both already-known
 * peers and freshly-bonded devices in one shot.
 *
 * @property macAddress Uppercased MAC. Doubles as the stable key
 *                       in [androidx.compose.foundation.lazy.LazyColumn].
 * @property displayName Friendly name surfaced in the row, falling
 *                       back to the MAC if neither the peer profile
 *                       nor the radio name is populated.
 * @property selected Whether the member is currently included in
 *                    the new group.
 */
data class CreateGroupCandidate(
    val macAddress: String,
    val displayName: String,
    val selected: Boolean,
)

/**
 * MVI screen state for [com.example.bluewave_mobile.ui.screens.CreateGroupScreen].
 *
 * The flow:
 *  1. [Idle] — first frame; the candidate list is being populated.
 *  2. [Editing] — user is typing the name + ticking members. The
 *     CTA lights up only when [canCreate] becomes `true`.
 *  3. [Submitting] — `createGroup` is in flight; CTA is disabled
 *     so a slow Bluetooth radio doesn't double-create the row.
 *  4. [Created] — terminal state delivered to the screen so it can
 *     navigate to the freshly-issued group chat. Carries [groupId].
 *  5. [Error] — terminal-but-recoverable state; the screen surfaces
 *     [message] in a snackbar and the user can retry.
 */
sealed interface CreateGroupUiState {

    /** Initial state before the candidate list has been populated. */
    data object Idle : CreateGroupUiState

    /**
     * User is editing the group. [name] is the typed string,
     * [candidates] is the populated picker list, and [canCreate]
     * is `true` exactly when the user has typed at least one
     * non-blank character and selected at least two members
     * (a self-only "group" would just be a chat with yourself).
     */
    data class Editing(
        val name: String,
        val candidates: List<CreateGroupCandidate>,
    ) : CreateGroupUiState {
        val selectedCount: Int = candidates.count(CreateGroupCandidate::selected)
        val canCreate: Boolean = name.isNotBlank() && selectedCount >= 1
    }

    /**
     * Repository call is in flight. The screen keeps the entered
     * name and selections visible (to give the user something to
     * look at) but disables the CTA / interaction.
     */
    data class Submitting(
        val name: String,
        val candidates: List<CreateGroupCandidate>,
    ) : CreateGroupUiState

    /**
     * Repository handed back the new group's stable id; the screen
     * navigates the user into the freshly-created group chat.
     */
    data class Created(val groupId: String) : CreateGroupUiState

    /**
     * Repository or radio failed; the screen surfaces [message] and
     * lets the user retry without leaving the form.
     */
    data class Error(val message: String) : CreateGroupUiState
}

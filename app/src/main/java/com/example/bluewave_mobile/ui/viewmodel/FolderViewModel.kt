package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.data.ChatFolderEntity
import com.example.bluewave_mobile.data.ConversationSummary
import com.example.bluewave_mobile.data.FolderRepository
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.PeerFolderAssignmentEntity
import com.example.bluewave_mobile.data.PeerProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Viewmodel powering the folders-management screen.
 *
 * Combines:
 *  * the live folder list ([FolderRepository.observeFolders]);
 *  * the peer→folder bridge ([FolderRepository.observeAssignments]);
 *  * the local conversation roster ([MessageRepository.observeAllConversations])
 *    so the membership picker can list every peer the user has
 *    actually chatted with;
 *  * the peer-profile cache so each row in the picker reads as
 *    "Алекс Иванов" rather than the raw MAC.
 *
 * Setters delegate to [FolderRepository] and never block — the UI
 * fires-and-forgets and the StateFlow re-emits as soon as Room
 * publishes the change.
 */
class FolderViewModel(
    private val folderRepository: FolderRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    /** Snapshot consumed by the screen — folders + assignments + peers. */
    data class UiState(
        val folders: List<ChatFolderEntity>,
        val assignments: List<PeerFolderAssignmentEntity>,
        val peers: List<PeerSummary>,
    ) {
        companion object {
            val EMPTY: UiState = UiState(
                folders = emptyList(),
                assignments = emptyList(),
                peers = emptyList(),
            )
        }
    }

    /** Single peer entry rendered inside the membership picker. */
    data class PeerSummary(
        val macAddress: String,
        val displayName: String,
    )

    val uiState: StateFlow<UiState> = combine(
        folderRepository.observeFolders(),
        folderRepository.observeAssignments(),
        messageRepository.observeAllConversations(),
        messageRepository.observeAllPeerProfiles(),
    ) { folders, assignments, conversations, profiles ->
        val profilesByMac: Map<String, PeerProfileEntity> =
            profiles.associateBy { it.macAddress.uppercase() }
        UiState(
            folders = folders,
            assignments = assignments,
            peers = conversations.map { summary -> summary.toSummary(profilesByMac) },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = UiState.EMPTY,
    )

    /** Create a new folder; trimming + non-empty check at call site. */
    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { folderRepository.createFolder(name) }
    }

    /** Rename an existing folder. Built-ins are renameable too. */
    fun renameFolder(folderId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { folderRepository.renameFolder(folderId, newName) }
    }

    /** Delete a folder; CASCADE drops every assignment. */
    fun deleteFolder(folderId: String) {
        viewModelScope.launch { folderRepository.deleteFolder(folderId) }
    }

    /** Toggle the (peer, folder) assignment row. */
    fun toggleAssignment(peerId: String, folderId: String) {
        val state = uiState.value
        val canonical = peerId.uppercase()
        val assigned = state.assignments.any {
            it.peerId.equals(canonical, ignoreCase = true) && it.folderId == folderId
        }
        viewModelScope.launch {
            if (assigned) {
                folderRepository.unassign(canonical, folderId)
            } else {
                folderRepository.assign(canonical, folderId)
            }
        }
    }

    private fun ConversationSummary.toSummary(
        profilesByMac: Map<String, PeerProfileEntity>,
    ): PeerSummary {
        val mac = macAddress.uppercase()
        val profileName = profilesByMac[mac]?.displayName?.takeUnless(String::isBlank)
        val name = profileName
            ?: lastMessage.senderName.takeUnless(String::isBlank)
            ?: mac
        return PeerSummary(macAddress = mac, displayName = name)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                FolderViewModel(
                    folderRepository = app.container.folderRepository,
                    messageRepository = app.container.messageRepository,
                )
            }
        }
    }
}

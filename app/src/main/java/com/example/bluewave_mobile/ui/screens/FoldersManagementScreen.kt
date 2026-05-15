package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.BuiltInFolder
import com.example.bluewave_mobile.data.ChatFolderEntity
import com.example.bluewave_mobile.ui.viewmodel.FolderViewModel

/**
 * Folders management screen.
 *
 * Layout:
 *  * Top bar with a back arrow and the "Folders" title.
 *  * `LazyColumn` of folder rows — built-ins first, then user
 *    folders. Each row shows the folder name and the number of
 *    peers currently assigned, with an edit and delete affordance.
 *  * Tapping a row pops a membership-picker dialog that lists every
 *    peer with persisted history; checkboxes mirror the live
 *    assignment state and toggling fires through the ViewModel.
 *  * `Add folder` extended FAB pops a name-entry dialog.
 *
 * Built-in folders ("Work" / "Family") are renamable but never
 * deletable — the delete affordance is hidden for them so the chip
 * row above the device list always has a stable taxonomy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersManagementScreen(
    onClose: () -> Unit,
    viewModel: FolderViewModel = viewModel(factory = FolderViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatFolderEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatFolderEntity?>(null) }
    var membershipTarget by remember { mutableStateOf<ChatFolderEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.folders_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(text = stringResource(id = R.string.folders_add)) },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.folders, key = ChatFolderEntity::id) { folder ->
                    FolderRow(
                        folder = folder,
                        memberCount = state.assignments.count { it.folderId == folder.id },
                        onTap = { membershipTarget = folder },
                        onRenameClick = { renameTarget = folder },
                        onDeleteClick = { deleteTarget = folder },
                    )
                }
            }
        }
    }

    if (showCreate) {
        FolderNameDialog(
            titleResId = R.string.folders_create_dialog_title,
            initialValue = "",
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }

    renameTarget?.let { folder ->
        FolderNameDialog(
            titleResId = R.string.folders_rename_dialog_title,
            initialValue = folder.displayName(),
            onConfirm = { name ->
                viewModel.renameFolder(folder.id, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFolder(folder.id)
                        deleteTarget = null
                    },
                ) {
                    Text(text = stringResource(id = R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            title = {
                Text(text = stringResource(id = R.string.folders_delete_dialog_title))
            },
            text = {
                Text(text = stringResource(id = R.string.folders_delete_dialog_message))
            },
        )
    }

    membershipTarget?.let { folder ->
        MembershipPickerDialog(
            folderName = folder.displayName(),
            peers = state.peers,
            assigned = state.assignments
                .filter { it.folderId == folder.id }
                .map { it.peerId.uppercase() }
                .toSet(),
            onToggle = { peerId -> viewModel.toggleAssignment(peerId, folder.id) },
            onDismiss = { membershipTarget = null },
        )
    }
}

@Composable
private fun FolderRow(
    folder: ChatFolderEntity,
    memberCount: Int,
    onTap: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        id = R.string.folders_member_count,
                        memberCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRenameClick) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(id = R.string.action_rename),
                )
            }
            if (folder.builtInKey == null) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(id = R.string.action_delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderNameDialog(
    titleResId: Int,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(id = R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        },
        title = { Text(text = stringResource(id = titleResId)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = stringResource(id = R.string.folders_name_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun MembershipPickerDialog(
    folderName: String,
    peers: List<FolderViewModel.PeerSummary>,
    assigned: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_done))
            }
        },
        title = { Text(text = folderName) },
        text = {
            if (peers.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.folders_empty_section),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(items = peers, key = FolderViewModel.PeerSummary::macAddress) { peer ->
                        PeerCheckRow(
                            peer = peer,
                            checked = assigned.contains(peer.macAddress.uppercase()),
                            onToggle = { onToggle(peer.macAddress) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}

@Composable
private fun PeerCheckRow(
    peer: FolderViewModel.PeerSummary,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = peer.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Resolves the user-visible label for a folder, falling back to the
 * built-in localised string when the row was seeded by the
 * [com.example.bluewave_mobile.data.FolderRepository].
 */
@Composable
private fun ChatFolderEntity.displayName(): String = when (builtInKey) {
    BuiltInFolder.WORK -> stringResource(id = R.string.folders_builtin_work)
    BuiltInFolder.FAMILY -> stringResource(id = R.string.folders_builtin_family)
    else -> name.ifBlank { id }
}

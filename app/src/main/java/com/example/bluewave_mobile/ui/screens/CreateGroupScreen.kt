package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.intent.CreateGroupIntent
import com.example.bluewave_mobile.ui.state.CreateGroupCandidate
import com.example.bluewave_mobile.ui.state.CreateGroupUiState
import com.example.bluewave_mobile.ui.viewmodel.CreateGroupViewModel

/**
 * Form screen for creating a new multi-peer group chat.
 *
 * Two stacked sections inside a [LazyColumn]:
 *  * sticky-style header — group name field and "members selected"
 *    label;
 *  * one [MemberRow] per [CreateGroupCandidate] in the picker, each
 *    a tappable row with a checkbox.
 *
 * The CTA at the bottom calls
 * [CreateGroupViewModel.handleIntent] with [CreateGroupIntent.Submit];
 * a [LaunchedEffect] on [CreateGroupUiState.Created] forwards the
 * minted group id back to the navigator via [onGroupCreated].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onClose: () -> Unit,
    onGroupCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CreateGroupViewModel = viewModel(factory = CreateGroupViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val isCreated = uiState is CreateGroupUiState.Created
    val errorMessage = (uiState as? CreateGroupUiState.Error)?.message
    LaunchedEffect(isCreated, errorMessage) {
        when (val state = uiState) {
            is CreateGroupUiState.Created -> onGroupCreated(state.groupId)
            is CreateGroupUiState.Error -> {
                snackbarHostState.showSnackbar(state.message)
            }
            else -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.create_group_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.action_close),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CreateGroupBody(
                state = uiState,
                onNameChange = { viewModel.handleIntent(CreateGroupIntent.UpdateName(it)) },
                onToggleMember = { viewModel.handleIntent(CreateGroupIntent.ToggleMember(it)) },
                onSubmit = { viewModel.handleIntent(CreateGroupIntent.Submit) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun CreateGroupBody(
    state: CreateGroupUiState,
    onNameChange: (String) -> Unit,
    onToggleMember: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (name, candidates, busy, canSubmit) = when (state) {
        is CreateGroupUiState.Editing -> Quad(
            name = state.name,
            candidates = state.candidates,
            busy = false,
            canSubmit = state.canCreate,
        )
        is CreateGroupUiState.Submitting -> Quad(
            name = state.name,
            candidates = state.candidates,
            busy = true,
            canSubmit = false,
        )
        else -> Quad(name = "", candidates = emptyList(), busy = false, canSubmit = false)
    }
    Column(modifier = modifier) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(stringResource(id = R.string.create_group_name_placeholder)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Text(
            text = stringResource(
                id = R.string.create_group_members_label,
                candidates.count(CreateGroupCandidate::selected),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.create_group_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(
                        items = candidates,
                        key = { candidate -> candidate.macAddress },
                    ) { candidate ->
                        MemberRow(
                            candidate = candidate,
                            enabled = !busy,
                            onClick = { onToggleMember(candidate.macAddress) },
                        )
                    }
                }
            }
        }
        Button(
            onClick = onSubmit,
            enabled = canSubmit && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(text = stringResource(id = R.string.create_group_create))
        }
    }
}

private data class Quad(
    val name: String,
    val candidates: List<CreateGroupCandidate>,
    val busy: Boolean,
    val canSubmit: Boolean,
)

@Composable
private fun MemberRow(
    candidate: CreateGroupCandidate,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemberAvatar(displayName = candidate.displayName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidate.macAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(
            checked = candidate.selected,
            enabled = enabled,
            onCheckedChange = { onClick() },
        )
    }
}

@Composable
private fun MemberAvatar(displayName: String) {
    val initial = displayName.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

package com.example.bluewave_mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.ui.components.InitialAvatar
import com.example.bluewave_mobile.ui.components.SectionHeader
import com.example.bluewave_mobile.ui.components.SettingsCard
import com.example.bluewave_mobile.ui.components.SettingsRow
import com.example.bluewave_mobile.ui.components.SettingsRowDivider
import com.example.bluewave_mobile.ui.components.pressScale
import com.example.bluewave_mobile.ui.theme.AccentCyan
import com.example.bluewave_mobile.ui.theme.AccentIndigo
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.viewmodel.ProfileViewModel

/**
 * Profile tab — modern redesign.
 *
 * Anchored by a large gradient avatar with a floating camera badge
 * (taps open the system photo picker, exactly like the previous
 * iteration). Below it: an "О себе" / "About" card that surfaces
 * the bio in a relaxed serif-ish typographic block, followed by a
 * stack of [SettingsRow]s for the individual editable fields
 * (display name, handle, privacy, QR share).
 *
 * The composable owns three small dialogs (one per editable field)
 * so the row layout stays uniform and the keyboard only opens
 * deliberately. Validation rules are unchanged from the previous
 * iteration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onShareQrClick: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val profile: LocalProfile by viewModel.profile.collectAsStateWithLifecycle()

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.setAvatarUri(uri.toString())
        }
    }

    var editingNameOpen by rememberSaveable { mutableStateOf(false) }
    var editingHandleOpen by rememberSaveable { mutableStateOf(false) }
    var editingBioOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.profile_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            AvatarBlock(
                profile = profile,
                onPickAvatar = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
            )

            Spacer(modifier = Modifier.height(24.dp))

            AboutCard(
                bio = profile.bio,
                onEditBio = { editingBioOpen = true },
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(title = stringResource(id = R.string.profile_title))
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Filled.Person,
                        title = stringResource(id = R.string.profile_action_edit_name),
                        subtitle = profile.displayName.ifBlank {
                            stringResource(id = R.string.profile_default_name)
                        },
                        iconTint = BrandBlue,
                        onClick = { editingNameOpen = true },
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Filled.AlternateEmail,
                        title = stringResource(id = R.string.profile_action_edit_handle),
                        subtitle = profile.handle.ifBlank {
                            stringResource(id = R.string.profile_handle_placeholder)
                        },
                        iconTint = AccentIndigo,
                        onClick = { editingHandleOpen = true },
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Filled.QrCode2,
                        title = stringResource(id = R.string.profile_action_share_qr),
                        iconTint = BrandBlue,
                        onClick = onShareQrClick,
                    )
                }
            }
        }
    }

    if (editingNameOpen) {
        FieldEditDialog(
            titleResId = R.string.profile_edit_name_title,
            initialValue = profile.displayName,
            placeholderResId = R.string.profile_display_name_placeholder,
            capitalization = KeyboardCapitalization.Words,
            onConfirm = {
                viewModel.setDisplayName(it)
                editingNameOpen = false
            },
            onDismiss = { editingNameOpen = false },
        )
    }
    if (editingHandleOpen) {
        FieldEditDialog(
            titleResId = R.string.profile_edit_handle_title,
            initialValue = profile.handle,
            placeholderResId = R.string.profile_handle_placeholder,
            capitalization = KeyboardCapitalization.None,
            onConfirm = {
                viewModel.setHandle(it)
                editingHandleOpen = false
            },
            onDismiss = { editingHandleOpen = false },
        )
    }
    if (editingBioOpen) {
        FieldEditDialog(
            titleResId = R.string.profile_edit_bio_title,
            initialValue = profile.bio,
            placeholderResId = R.string.profile_bio_placeholder,
            capitalization = KeyboardCapitalization.Sentences,
            multiLine = true,
            onConfirm = {
                viewModel.setBio(it)
                editingBioOpen = false
            },
            onDismiss = { editingBioOpen = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Avatar block
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun AvatarBlock(
    profile: LocalProfile,
    onPickAvatar: () -> Unit,
) {
    val displayName = profile.displayName.ifBlank {
        stringResource(id = R.string.profile_default_name)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .pressScale()
                .clickable(role = Role.Button, onClick = onPickAvatar),
            contentAlignment = Alignment.Center,
        ) {
            val avatarUri = profile.avatarUri
            if (avatarUri.isNotBlank()) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape),
                )
            } else {
                InitialAvatar(name = displayName, size = 120)
            }
            // Floating camera badge in the bottom-right corner.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(BrandBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = stringResource(id = R.string.profile_avatar_action),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (profile.handle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = profile.handle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// About card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard(
    bio: String,
    onEditBio: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(role = Role.Button, onClick = onEditBio),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.profile_section_about),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(id = R.string.profile_edit_bio_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = bio.ifBlank {
                    stringResource(id = R.string.profile_default_bio)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (bio.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Field-edit dialog
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun FieldEditDialog(
    titleResId: Int,
    initialValue: String,
    placeholderResId: Int,
    capitalization: KeyboardCapitalization,
    multiLine: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = titleResId),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(stringResource(id = placeholderResId)) },
                singleLine = !multiLine,
                maxLines = if (multiLine) 6 else 1,
                keyboardOptions = KeyboardOptions(capitalization = capitalization),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_edit_field"),
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) {
                Text(stringResource(id = R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_cancel))
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

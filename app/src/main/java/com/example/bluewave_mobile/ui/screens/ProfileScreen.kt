package com.example.bluewave_mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.ui.viewmodel.ProfileViewModel

/**
 * The Profile tab.
 *
 * Renders the locally-persisted profile card (avatar / name /
 * @handle / bio) with inline edit controls. The
 * [ProfileViewModel] forwards every edit to
 * [com.example.bluewave_mobile.preferences.UserPreferencesRepository.setLocalProfile];
 * a long-lived collector inside `BlueWaveApplication` re-broadcasts
 * the updated card to every secure peer over the
 * `PROFILE_METADATA` Bluetooth frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onShareQrClick: () -> Unit,
    viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory),
) {
    val profile: LocalProfile by viewModel.profile.collectAsState()

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.setAvatarUri(uri.toString())
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.profile_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AvatarPicker(
                    uri = profile.avatarUri,
                    onClick = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("profile_avatar_action"),
                ) {
                    Text(text = stringResource(id = R.string.profile_avatar_action))
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = profile.displayName,
                    onValueChange = viewModel::setDisplayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_display_name_field"),
                    label = { Text(text = stringResource(id = R.string.profile_display_name)) },
                    placeholder = {
                        Text(text = stringResource(id = R.string.profile_display_name_placeholder))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = profile.handle,
                    onValueChange = viewModel::setHandle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_handle_field"),
                    label = { Text(text = stringResource(id = R.string.profile_handle)) },
                    placeholder = {
                        Text(text = stringResource(id = R.string.profile_handle_placeholder))
                    },
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = profile.bio,
                    onValueChange = viewModel::setBio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_bio_field"),
                    label = { Text(text = stringResource(id = R.string.profile_bio)) },
                    placeholder = {
                        Text(text = stringResource(id = R.string.profile_bio_placeholder))
                    },
                    singleLine = false,
                    maxLines = 4,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onShareQrClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_share_qr_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCode,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.profile_share_qr))
                }
            }
        }
    }
}

/**
 * Round avatar control: delegates to the system Photo Picker via
 * [ActivityResultContracts.PickVisualMedia] when tapped, falls back
 * to the placeholder person icon when no avatar URI is set yet.
 */
@Composable
private fun AvatarPicker(
    uri: String,
    onClick: () -> Unit,
) {
    val placeholderTint = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(color = backgroundColor)
            .clickable(onClick = onClick)
            .testTag("profile_avatar_picker"),
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isBlank()) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = placeholderTint,
                modifier = Modifier.size(56.dp),
            )
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

package com.example.bluewave_mobile.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.data.PeerProfileEntity
import com.example.bluewave_mobile.ui.components.InitialAvatar
import com.example.bluewave_mobile.ui.viewmodel.PeerProfileViewModel

/**
 * Read-only profile screen for a remote peer.
 *
 * Shows the avatar, display name, @handle and bio that the peer
 * pushed to us via the `PROFILE_METADATA` Bluetooth frame. There
 * are no editing controls — everything here is owned by the other
 * device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerProfileScreen(
    deviceMac: String,
    onBack: () -> Unit,
    viewModel: PeerProfileViewModel = viewModel(
        factory = PeerProfileViewModel.Factory,
        key = deviceMac,
    ),
) {
    val profile: PeerProfileEntity? by viewModel.peerProfile.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.common_back),
                        )
                    }
                },
                title = { Text(text = stringResource(id = R.string.peer_profile_title)) },
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val displayName = profile?.displayName?.takeUnless { it.isBlank() }
                ?: stringResource(id = R.string.peer_profile_default_name)

            val avatarUri = profile?.avatarUri?.orEmpty() ?: ""
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

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            val handle = profile?.handle?.takeUnless { it.isBlank() }
            if (handle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = handle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            val bio = profile?.bio?.takeUnless { it.isBlank() }
            Text(
                text = bio ?: stringResource(id = R.string.peer_profile_no_bio),
                style = MaterialTheme.typography.bodyLarge,
                color = if (bio == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

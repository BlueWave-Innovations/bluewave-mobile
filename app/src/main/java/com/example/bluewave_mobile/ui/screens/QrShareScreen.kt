package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R

/**
 * Stub for the QR share / scan screen. Phase 7 fills in:
 *  * QR encoder backed by zxing — payload =
 *    `bluewave://contact?mac=…&id=…&tag=…`;
 *  * scan surface (Camera + ML Kit) that decodes a peer QR back
 *    into [onContactScanned] with the peer MAC;
 *  * "share this code" Material 3 chip + system-share intent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShareScreen(
    onClose: () -> Unit,
    onContactScanned: (String) -> Unit,
) {
    @Suppress("UNUSED_PARAMETER")
    fun _markScanned() = onContactScanned("")
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.qr_share_title),
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
                    .padding(16.dp),
            ) {
                Text(text = stringResource(id = R.string.qr_share_placeholder))
            }
        }
    }
}

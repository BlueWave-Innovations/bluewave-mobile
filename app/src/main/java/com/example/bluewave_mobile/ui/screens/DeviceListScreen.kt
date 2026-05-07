package com.example.bluewave_mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Scaffold-based screen that lists Bluetooth peers and lets the user
 * jump into a one-on-one chat by tapping a device row.
 *
 * The whole layout is wrapped in a Material 3 [Scaffold] so that the
 * top app bar, the discovery FAB and the (future) snackbar host can
 * compose without leaking padding into each other. The
 * `innerPadding` reserved by [Scaffold] is forwarded onto the root
 * [Box] via [Modifier.padding]; this is what prevents the system
 * status bar / navigation bar from clipping the content under the
 * Edge-to-Edge layout configured in [com.example.bluewave_mobile.MainActivity].
 *
 * `contentWindowInsets = WindowInsets(0)` is intentional: the root
 * `Surface` in `MainActivity` already applies `systemBarsPadding()`,
 * so letting [Scaffold] re-apply its default insets would double-pad
 * the content on phones with a notch / nav bar.
 *
 * @param onDeviceClick Invoked with the MAC address of the peer the
 *                      user picked. The caller is expected to navigate
 *                      to the chat destination defined in
 *                      [com.example.bluewave_mobile.ui.navigation.ChatRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onDeviceClick: (String) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(text = "BlueWave") },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* discovery wired in later steps */ }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Rescan for nearby devices"
                )
            }
        }
    ) { innerPadding ->
        // innerPadding reserves the area covered by the TopAppBar / FAB /
        // SnackbarHost. Forwarding it onto the root container is what
        // prevents content from rendering underneath those slots.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = { onDeviceClick("00:11:22:33:44:55") }) {
                Text("Go to Chat with dummy device")
            }
        }
    }
}

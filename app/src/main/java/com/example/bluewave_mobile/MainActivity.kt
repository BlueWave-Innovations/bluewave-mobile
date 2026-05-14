package com.example.bluewave_mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.bluewave_mobile.ui.components.AdaptiveWindowInfo
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.TwoPaneLayout
import com.example.bluewave_mobile.ui.navigation.ChatRoute
import com.example.bluewave_mobile.ui.navigation.DeviceListRoute
import com.example.bluewave_mobile.ui.screens.ChatScreen
import com.example.bluewave_mobile.ui.screens.DeviceListScreen
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            BlueWaveTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AdaptiveAppRoot()
                }
            }
        }
    }
}

/**
 * Root composable that picks between the single-pane Navigation
 * Compose flow and a tablet/foldable two-pane layout based on the
 * available width. The decision is delegated to
 * [TwoPaneLayout]; this composable is just the glue that holds the
 * "active peer" selection state when the user is in two-pane mode.
 */
@Composable
fun AdaptiveAppRoot() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val info = AdaptiveWindowInfo(widthDp = maxWidth, heightDp = maxHeight)
        if (info.isExpandedWidth) {
            var selectedMac: String? by rememberSaveable { mutableStateOf<String?>(null) }
            var selectedName: String by rememberSaveable { mutableStateOf("") }
            TwoPaneLayout(
                primary = {
                    DeviceListScreen(
                        onDeviceClick = { mac, name ->
                            selectedMac = mac
                            selectedName = name
                        },
                    )
                },
                secondary = {
                    val mac = selectedMac
                    if (mac == null) {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = "No conversation selected",
                            message = "Pick a device on the left to start chatting.",
                        )
                    } else {
                        ChatScreen(
                            deviceMac = mac,
                            deviceName = selectedName,
                        )
                    }
                },
            )
        } else {
            MainNavigation()
        }
    }
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = DeviceListRoute,
    ) {
        composable<DeviceListRoute> {
            DeviceListScreen(
                onDeviceClick = { mac, name ->
                    navController.navigate(
                        ChatRoute(deviceMac = mac, deviceName = name),
                    )
                },
            )
        }
        composable<ChatRoute> { backStackEntry ->
            val chatRoute: ChatRoute = backStackEntry.toRoute()
            ChatScreen(
                deviceMac = chatRoute.deviceMac,
                deviceName = chatRoute.deviceName,
            )
        }
    }
}

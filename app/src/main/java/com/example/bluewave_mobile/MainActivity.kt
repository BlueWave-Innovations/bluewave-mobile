package com.example.bluewave_mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluewave_mobile.preferences.AppLanguage
import com.example.bluewave_mobile.preferences.ThemeMode
import com.example.bluewave_mobile.preferences.UserPreferencesRepository
import com.example.bluewave_mobile.ui.components.AdaptiveWindowInfo
import com.example.bluewave_mobile.ui.components.EmptyStateView
import com.example.bluewave_mobile.ui.components.TwoPaneLayout
import com.example.bluewave_mobile.ui.navigation.MainScaffold
import com.example.bluewave_mobile.ui.screens.ChatScreen
import com.example.bluewave_mobile.ui.screens.DeviceListScreen
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Apply the persisted language preference *before* the first
        // composition runs so every `stringResource()` lookup reads
        // from the right `values-*` bundle. We block on the first
        // emission of the preferences flow because the call is local
        // file I/O — typically <1ms — and skipping it here would
        // cause a one-frame flash of the system locale.
        val prefs = (applicationContext as BlueWaveApplication).container.userPreferencesRepository
        applyPersistedLanguage(prefs)

        setContent {
            // Re-read theme + language reactively so the user can
            // change either from the Settings screen and have the
            // effect propagate without an app restart.
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val appLanguage by prefs.appLanguage.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
            val context = LocalContext.current
            LaunchedEffect(appLanguage) {
                AppCompatDelegate.setApplicationLocales(appLanguage.toLocaleList())
                @Suppress("UNUSED_EXPRESSION") context // keep reference so re-composition wakes on locale flip
            }

            BlueWaveTheme(themeMode = themeMode) {
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

    /**
     * Read the persisted [AppLanguage] once, on the main thread,
     * and feed it into [AppCompatDelegate.setApplicationLocales].
     *
     * `runBlocking` is intentional: Compose has not started yet,
     * the read is on the cached DataStore disk-thread (kept warm
     * by the property delegate at process start), and skipping
     * this synchronous prime would cause the wrong-locale flash
     * mentioned above.
     */
    private fun applyPersistedLanguage(prefs: UserPreferencesRepository) {
        val initial = runBlocking { prefs.appLanguage.first() }
        AppCompatDelegate.setApplicationLocales(initial.toLocaleList())
    }
}

/**
 * Root composable that picks between the bottom-nav single-pane
 * flow and the legacy two-pane tablet layout based on the
 * available width.
 *
 * Compact / medium widths route through [MainScaffold] which owns
 * the bottom-nav `NavigationBar` and the chats / settings /
 * profile destinations. Expanded widths fall back to the
 * tablet-friendly [TwoPaneLayout] that surfaces a master-detail
 * chat view.
 */
@Composable
fun AdaptiveAppRoot() {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val info = AdaptiveWindowInfo(widthDp = maxWidth, heightDp = maxHeight)
        if (info.isExpandedWidth) {
            var selectedMac: String? by rememberSaveable { mutableStateOf<String?>(null) }
            TwoPaneLayout(
                primary = {
                    DeviceListScreen(
                        onDeviceClick = { mac -> selectedMac = mac },
                    )
                },
                secondary = {
                    val mac = selectedMac
                    if (mac == null) {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = stringResource(id = R.string.chat_no_selection_title),
                            message = stringResource(id = R.string.chat_no_selection_message),
                        )
                    } else {
                        ChatScreen(deviceMac = mac)
                    }
                },
            )
        } else {
            MainScaffold()
        }
    }
}

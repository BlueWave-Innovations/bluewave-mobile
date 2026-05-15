package com.example.bluewave_mobile

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.example.bluewave_mobile.ui.screens.GroupChatScreen
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import com.example.bluewave_mobile.utils.BlueWaveLogger
import com.example.bluewave_mobile.utils.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = (newBase.applicationContext as BlueWaveApplication)
            .container.userPreferencesRepository
        val appLanguage = try {
            runBlocking { prefs.appLanguage.first() }
        } catch (_: Exception) {
            AppLanguage.SYSTEM
        }
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlueWaveLogger.d("MainActivity", "onCreate")
        enableEdgeToEdge()

        val prefs = (applicationContext as BlueWaveApplication).container.userPreferencesRepository

        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val appLanguage by prefs.appLanguage.collectAsStateWithLifecycle(initialValue = AppLanguage.SYSTEM)
            val context = LocalContext.current

            EnsureBluetoothEnabled()

            // When the user picks a different language we restart the
            // task cleanly instead of recreating the activity.
            // `LocaleHelper.wrapContext` in `attachBaseContext` will
            // pick the new value up on the fresh start.
            LaunchedEffect(appLanguage) {
                val currentLocale = context.resources.configuration.locales.get(0)
                val desiredLocale = when (appLanguage) {
                    AppLanguage.ENGLISH -> java.util.Locale.forLanguageTag("en")
                    AppLanguage.RUSSIAN -> java.util.Locale.forLanguageTag("ru")
                    AppLanguage.SYSTEM -> java.util.Locale.getDefault()
                }
                if (currentLocale.language == desiredLocale.language) return@LaunchedEffect
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
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
            var selectedGroupId: String? by rememberSaveable { mutableStateOf<String?>(null) }
            TwoPaneLayout(
                primary = {
                    DeviceListScreen(
                        onDeviceClick = { mac ->
                            selectedMac = mac
                            selectedGroupId = null
                        },
                        onGroupClick = { groupId ->
                            selectedGroupId = groupId
                            selectedMac = null
                        },
                    )
                },
                secondary = {
                    when {
                        selectedGroupId != null -> {
                            GroupChatScreen(
                                groupId = selectedGroupId!!,
                                onBack = { selectedGroupId = null },
                            )
                        }
                        selectedMac != null -> {
                            ChatScreen(
                                deviceMac = selectedMac!!,
                                onBack = { selectedMac = null },
                            )
                        }
                        else -> {
                            EmptyStateView(
                                icon = Icons.AutoMirrored.Filled.Chat,
                                title = stringResource(id = R.string.chat_no_selection_title),
                                message = stringResource(id = R.string.chat_no_selection_message),
                            )
                        }
                    }
                },
            )
        } else {
            MainScaffold()
        }
    }
}

/**
 * Side-effect composable that fires the system
 * `BluetoothAdapter.ACTION_REQUEST_ENABLE` dialog whenever the user
 * brings BlueWave back to the foreground with the adapter off.
 *
 * Hooked into the root content so it runs once per
 * [androidx.compose.runtime.Composer] instance and listens to the
 * activity's lifecycle. The dialog itself is fully system-owned, so
 * we just observe its result through the activity-result launcher
 * to flip a local "user just decided" flag and avoid re-firing the
 * dialog inside the same resumed slice (which would create a
 * dialog-spam loop if the user picked "Deny"). The flag clears on
 * every full ON_STOP → ON_RESUME transition so a subsequent return
 * to the foreground asks again — that matches the user's request
 * "if Bluetooth is off the program requests everything necessary
 * so that messages are delivered constantly".
 *
 * Returns no UI of its own — it is a pure side-effect node.
 */
@Composable
private fun EnsureBluetoothEnabled() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = remember(context) {
        (context.applicationContext as BlueWaveApplication).container
    }
    val adapter: BluetoothAdapter? = container.bluetoothAdapter

    // Per-resumption guard: once the launcher closes (whether the
    // user approved or denied) we don't re-prompt until the activity
    // goes through a full STOP → RESUME cycle. Without this the
    // launcher's onResult would arrive, the next recomposition would
    // still see `adapter.isEnabled == false`, and we would re-prompt
    // immediately — the exact infinite-dialog loop the comment
    // above warns against.
    var alreadyPromptedThisResume by remember { mutableStateOf(false) }

    val enableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        // We don't actually care about the resultCode — the next
        // adapter.isEnabled probe is the source of truth. We just
        // need the lambda to exist so the launcher is wired up.
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    BlueWaveLogger.d("EnsureBluetoothEnabled", "ON_RESUME probe")
                    if (adapter == null) return@LifecycleEventObserver
                    if (alreadyPromptedThisResume) return@LifecycleEventObserver
                    if (adapter.isEnabled) return@LifecycleEventObserver
                    BlueWaveLogger.i("EnsureBluetoothEnabled", "Bluetooth off — launching enable dialog")
                    alreadyPromptedThisResume = true
                    runCatching {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    BlueWaveLogger.d("EnsureBluetoothEnabled", "ON_STOP")
                    alreadyPromptedThisResume = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}



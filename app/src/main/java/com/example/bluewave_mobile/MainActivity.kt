package com.example.bluewave_mobile

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.bluewave_mobile.network.BlueWaveBluetoothService
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

    /**
     * Result launcher for the Android 13+
     * [Manifest.permission.POST_NOTIFICATIONS] runtime dialog. We
     * fire it once per cold launch when the permission is missing
     * so the persistent foreground-service notification is
     * visible — the FGS itself runs regardless, the user just
     * loses the "BlueWave is listening" indicator if they deny.
     */
    private val notificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // The Application class already started the FGS at process
        // launch, but Android may have killed the process between
        // sessions and recreated only the Activity; calling start()
        // again is idempotent and brings the listener back online
        // if it was reaped.
        BlueWaveBluetoothService.start(this)

        // Fire the POST_NOTIFICATIONS dialog the first time the
        // user lands on the home screen. We deliberately do NOT
        // gate the rest of the app on this — a denied notification
        // permission is recoverable through system Settings and
        // does not block messaging functionality.
        maybeRequestPostNotificationsPermission()

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

            // BlueWave is fundamentally a Bluetooth messenger — if
            // the user's adapter is off, the entire app degrades to
            // a local message-history viewer. We prompt the system
            // ACTION_REQUEST_ENABLE dialog as soon as the user
            // returns to the foreground with the adapter off, then
            // again on every subsequent ON_RESUME (e.g. user toggled
            // BT off in quick settings while inside the app). We
            // never auto-loop on the dialog dismissal: the user gets
            // to deny it for the current session, and the next
            // ON_RESUME edge will re-ask.
            //
            // Note: launching the system intent does NOT require
            // BLUETOOTH_CONNECT runtime permission on API 33+ —
            // the launcher is a SystemUI activity, so we don't have
            // to chain it behind PermissionGateView.
            EnsureBluetoothEnabled()

            // Drive the per-app locale picker. The activity is a
            // `ComponentActivity`, not an `AppCompatActivity`, so
            // `AppCompatDelegate.setApplicationLocales` does NOT
            // auto-recreate the window for us — we have to call
            // [Activity.recreate] ourselves so every
            // `stringResource()` re-resolves against the new
            // `values-*` bundle.
            //
            // The guard compares against
            // [AppCompatDelegate.getApplicationLocales] (not against
            // a remembered Compose state) because Compose state is
            // wiped on every recreate. If we keyed off remembered
            // state, the first DataStore replay after a recreate
            // would re-trigger `recreate()` and the activity would
            // get stuck in an infinite recreation loop — that's the
            // "приложение лагает / нужно переустанавливать"
            // regression we saw on the live phone. Querying the
            // delegate is the source of truth across recreates: it
            // is already up to date by the time we run because
            // `applyPersistedLanguage` ran synchronously in
            // `onCreate` before `setContent`.
            LaunchedEffect(appLanguage) {
                val desired = appLanguage.toLocaleList()
                if (AppCompatDelegate.getApplicationLocales() == desired) return@LaunchedEffect
                AppCompatDelegate.setApplicationLocales(desired)
                (context as? Activity)?.recreate()
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
     * Re-asserts the foreground-service start on every
     * [onStart] edge so a process recreated by Android after a
     * memory-pressure kill (swipe-from-recents on a low-RAM
     * device) brings the accept-loop guarantee back the moment
     * the user re-opens the app. The service's start helper is
     * idempotent so this is a cheap no-op when the listener was
     * already alive.
     */
    override fun onStart() {
        super.onStart()
        BlueWaveBluetoothService.start(this)
    }

    /**
     * Idempotent POST_NOTIFICATIONS request used only on
     * Android 13+ where the permission is a runtime gate. On
     * older API levels notifications post unconditionally.
     */
    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    if (adapter == null) return@LifecycleEventObserver
                    if (alreadyPromptedThisResume) return@LifecycleEventObserver
                    if (adapter.isEnabled) return@LifecycleEventObserver
                    alreadyPromptedThisResume = true
                    runCatching {
                        enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    alreadyPromptedThisResume = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

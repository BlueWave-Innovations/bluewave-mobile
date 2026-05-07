package com.example.bluewave_mobile.ui.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.bluewave_mobile.network.PermissionManager

/**
 * Compose-friendly wrapper around [PermissionManager] that drives the
 * runtime permission UX through `ActivityResultContracts.RequestMultiplePermissions`.
 *
 * The composable utility returns a small holder containing:
 *
 *  * [BluetoothPermissionState.allGranted] — observable boolean used
 *    by the screen to decide whether to render the permission gate or
 *    the live device list.
 *  * [BluetoothPermissionState.missingPermissions] — the subset that
 *    is **not** yet granted; used by the rationale dialog.
 *  * [BluetoothPermissionState.requestPermissions] — fires the system
 *    permission dialog through the result launcher cached by
 *    [rememberLauncherForActivityResult].
 *
 * The launcher is created **once** per host composition via
 * [remember]; recreating it on every recomposition would cancel any
 * pending dialog. The result callback flips the `allGranted` flag
 * which automatically recomposes the consumer.
 */
class BluetoothPermissionState internal constructor(
    private val launch: (Array<String>) -> Unit,
    private val refresh: () -> Unit,
    val allGranted: Boolean,
    val missingPermissions: List<String>
) {
    /** Triggers the system permission dialog for missing permissions. */
    fun requestPermissions() {
        val missing = missingPermissions.toTypedArray()
        if (missing.isNotEmpty()) {
            launch(missing)
        } else {
            // Permissions might have changed externally (Settings app);
            // re-check so the consumer recomposes if state diverged.
            refresh()
        }
    }
}

/**
 * Remembers a [BluetoothPermissionState] tied to the current Activity.
 *
 * Usage:
 *
 * ```kotlin
 * val perms = rememberBluetoothPermissionState()
 * if (!perms.allGranted) {
 *     PermissionGateView(
 *         missing = perms.missingPermissions,
 *         onGrantClick = perms::requestPermissions
 *     )
 *     return@composable
 * }
 * DeviceListContent(/* … */)
 * ```
 */
@Composable
fun rememberBluetoothPermissionState(): BluetoothPermissionState {
    val context = LocalContext.current
    var allGranted by remember {
        mutableStateOf(PermissionManager.hasAllBluetoothPermissions(context))
    }
    var missing by remember {
        mutableStateOf(PermissionManager.missingBluetoothPermissions(context).toList())
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // We don't trust the per-permission map directly — re-check
        // through PermissionManager so the source of truth stays the
        // OS, not the dialog result.
        allGranted = PermissionManager.hasAllBluetoothPermissions(context)
        missing = PermissionManager.missingBluetoothPermissions(context).toList()
    }

    // Re-query once when the screen first composes so we don't show a
    // stale "denied" state if the user granted permissions in Settings
    // while the screen was off-screen.
    LaunchedEffect(Unit) {
        allGranted = PermissionManager.hasAllBluetoothPermissions(context)
        missing = PermissionManager.missingBluetoothPermissions(context).toList()
    }

    return remember(allGranted, missing) {
        BluetoothPermissionState(
            launch = { perms -> launcher.launch(perms) },
            refresh = {
                allGranted = PermissionManager.hasAllBluetoothPermissions(context)
                missing = PermissionManager.missingBluetoothPermissions(context).toList()
            },
            allGranted = allGranted,
            missingPermissions = missing
        )
    }
}

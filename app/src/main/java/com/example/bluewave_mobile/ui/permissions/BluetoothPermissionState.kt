package com.example.bluewave_mobile.ui.permissions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.example.bluewave_mobile.network.PermissionManager

/**
 * Compose-friendly wrapper around [PermissionManager] that drives the
 * runtime permission UX through `ActivityResultContracts.RequestMultiplePermissions`.
 *
 * In addition to the basic granted / missing split, the holder exposes
 * [permanentlyDenied], which flips to `true` once the user has tapped
 * "Don't ask again" at least once. When that happens the system
 * dialog will silently no-op on the next request, so we redirect the
 * user to the per-app permissions screen with a one-tap deep-link
 * intent (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) — that is the
 * one place where the user can flip the toggle back on without
 * navigating through three layers of system Settings.
 */
class BluetoothPermissionState internal constructor(
    private val launch: (Array<String>) -> Unit,
    private val openAppSettings: () -> Unit,
    private val refresh: () -> Unit,
    val allGranted: Boolean,
    val missingPermissions: List<String>,
    val permanentlyDenied: Boolean,
) {
    /**
     * Triggers the most useful next step:
     *
     *  * If the user has never permanently denied a required permission,
     *    fires the system "Allow" dialog.
     *  * If at least one required permission is permanently denied,
     *    deep-links to the BlueWave entry in Settings → Apps so the
     *    user can flip the toggle back on with a single tap.
     */
    fun requestPermissions() {
        val missing = missingPermissions.toTypedArray()
        when {
            missing.isEmpty() -> refresh()
            permanentlyDenied -> openAppSettings()
            else -> launch(missing)
        }
    }
}

@Composable
fun rememberBluetoothPermissionState(): BluetoothPermissionState {
    val context = LocalContext.current
    val activity = context as? Activity
    var allGranted by remember {
        mutableStateOf(PermissionManager.hasAllBluetoothPermissions(context))
    }
    var missing by remember {
        mutableStateOf(PermissionManager.missingBluetoothPermissions(context).toList())
    }
    // `permanentlyDenied` is updated only AFTER the user has interacted
    // with the runtime dialog at least once — Android's
    // `shouldShowRequestPermissionRationale` returns `false` both when
    // the user has never been asked yet AND after they tick
    // "Don't ask again", so we cannot rely on it for the initial state.
    // The launcher callback below sets it from the result.
    var permanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        allGranted = PermissionManager.hasAllBluetoothPermissions(context)
        missing = PermissionManager.missingBluetoothPermissions(context).toList()
        permanentlyDenied = if (activity == null) {
            false
        } else {
            results.any { (perm, granted) ->
                !granted && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
        }
    }

    LaunchedEffect(Unit) {
        allGranted = PermissionManager.hasAllBluetoothPermissions(context)
        missing = PermissionManager.missingBluetoothPermissions(context).toList()
    }

    return remember(allGranted, missing, permanentlyDenied) {
        BluetoothPermissionState(
            launch = { perms -> launcher.launch(perms) },
            openAppSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            },
            refresh = {
                allGranted = PermissionManager.hasAllBluetoothPermissions(context)
                missing = PermissionManager.missingBluetoothPermissions(context).toList()
            },
            allGranted = allGranted,
            missingPermissions = missing,
            permanentlyDenied = permanentlyDenied,
        )
    }
}

package com.example.bluewave_mobile.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralized utility for managing Bluetooth-related runtime permissions
 * across all Android API levels supported by BlueWave (minSdk 28 .. targetSdk 36).
 *
 * Permission model has changed significantly between API levels:
 *
 * * **API 30 and below (Android 11)** — required `BLUETOOTH`, `BLUETOOTH_ADMIN`
 *   and `ACCESS_FINE_LOCATION` (the latter because BLE scans were treated as
 *   a location signal).
 *
 * * **API 31+ (Android 12)** — Bluetooth permissions were decoupled from
 *   location. Apps that do **not** derive physical location from BLE beacons
 *   only need [Manifest.permission.BLUETOOTH_CONNECT] and
 *   [Manifest.permission.BLUETOOTH_SCAN] declared with the
 *   `usesPermissionFlags="neverForLocation"` attribute in the manifest.
 *
 * * **API 36 (Android 16)** — same runtime contract as API 31+, but with
 *   stricter enforcement of the *neverForLocation* flag and tighter handling
 *   of bond-loss / encryption-change events (see steps 29–31).
 *
 * BlueWave performs classic RFCOMM communication and never computes the
 * physical location of the device, so the manifest declares
 * `usesPermissionFlags="neverForLocation"` and we only request the new
 * `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` pair on API 31+.
 */
object PermissionManager {

    /**
     * Permissions required for the Bluetooth subsystem to operate on the
     * current device. The returned list depends on [Build.VERSION.SDK_INT]
     * and is therefore safe to feed directly into
     * `ActivityResultContracts.RequestMultiplePermissions`.
     *
     * @return immutable array of fully-qualified permission strings
     *         that MUST be granted before any RFCOMM operation is started.
     */
    fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ : Bluetooth permissions are no longer location-derived.
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            // API 28..30 : legacy permissions + ACCESS_FINE_LOCATION for discovery.
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    /**
     * Returns true if every entry returned by [requiredBluetoothPermissions]
     * is currently granted to the app for the supplied [context].
     *
     * Should be called from the UI layer **before** any call into
     * `BluetoothAdapter.startDiscovery()` or socket creation, in order to
     * surface the system permission dialog at the latest reasonable moment.
     */
    fun hasAllBluetoothPermissions(context: Context): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Filters [requiredBluetoothPermissions] down to the subset that is **not**
     * yet granted. Returning an empty array means the app may proceed with
     * Bluetooth operations immediately without prompting the user.
     */
    fun missingBluetoothPermissions(context: Context): Array<String> {
        return requiredBluetoothPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /**
     * Whether the user has granted the
     * [Manifest.permission.POST_NOTIFICATIONS] runtime permission.
     *
     * On API 32 and below the permission does not exist as a
     * runtime concept — notifications are always allowed unless the
     * user disabled them through the system Settings app — so this
     * helper returns `true` to keep callers free of API-level
     * branching. The result is consumed by [MainActivity] to decide
     * whether to fire the system permission dialog the first time
     * the user opens the device list.
     */
    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}

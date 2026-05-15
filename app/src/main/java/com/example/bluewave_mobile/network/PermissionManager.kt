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
        // minSdk is 31 (Android 12) so we only ever need the modern
        // split permissions.  BLUETOOTH_ADVERTISE is not required for
        // classic RFCOMM; POST_NOTIFICATIONS is needed on API 33+ but
        // is harmless to request on 31-32 (system auto-grants).
        return arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
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
}

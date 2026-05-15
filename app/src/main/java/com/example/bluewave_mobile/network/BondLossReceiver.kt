package com.example.bluewave_mobile.network

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.bluewave_mobile.utils.BlueWaveLogger
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.utils.parcelableExtra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that handles the **Android 16** bond / encryption
 * lifecycle events for a single peer.
 *
 * Android 16 introduced "Improved bond loss handling": when the local
 * adapter detects that the encryption keys for a previously-bonded peer
 * are missing, it no longer silently removes the bond. Instead it:
 *
 *  1. keeps the bond metadata so the user can re-pair without going
 *     through the full pairing dialog again;
 *  2. tears down the active ACL connection;
 *  3. broadcasts `BluetoothDevice.ACTION_KEY_MISSING` (handled here by
 *     pausing traffic), and later
 *     `BluetoothDevice.ACTION_ENCRYPTION_CHANGE` once a valid key is
 *     re-established — at which point we resume traffic and ask the
 *     caller to restart the [ConnectThread] for that peer.
 *
 * On `ACTION_KEY_MISSING` we MUST NOT call any
 * `removeBond()`-style API — the OS already preserves the metadata for
 * us. Instead we tell the [MessageRepository] to pause traffic for that
 * peer; transmission resumes once the encryption key is back.
 *
 * The [onEncryptionRestored] callback is invoked from the
 * receiver's dispatch thread; the caller decides which background scope
 * to use to spawn a fresh [ConnectThread] against the peer.
 */
class BondLossReceiver(
    private val repository: MessageRepository,
    private val scope: CoroutineScope,
    private val onEncryptionRestored: (BluetoothDevice) -> Unit = { /* no-op */ }
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val device: BluetoothDevice? =
            intent.parcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val mac = device?.address ?: run {
            BlueWaveLogger.w(TAG, "$action received without EXTRA_DEVICE")
            return
        }
        when (action) {
            ACTION_KEY_MISSING -> {
                BlueWaveLogger.i(TAG, "ACTION_KEY_MISSING for $mac — pausing network operations")
                scope.launch {
                    repository.pauseNetworkOperations(mac)
                }
            }
            ACTION_ENCRYPTION_CHANGE -> {
                BlueWaveLogger.i(TAG, "ACTION_ENCRYPTION_CHANGE for $mac — resuming and restarting ConnectThread")
                scope.launch {
                    repository.resumeNetworkOperations(mac)
                }
                onEncryptionRestored(device)
            }
            else -> Unit
        }
    }

    /**
     * Convenience method: registers this receiver against both the
     * `ACTION_KEY_MISSING` and `ACTION_ENCRYPTION_CHANGE` intent filters
     * on the supplied [context].
     */
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_KEY_MISSING)
            addAction(ACTION_ENCRYPTION_CHANGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(this, filter)
        }
    }

    /**
     * De-registers this receiver from the supplied [context]. Must be
     * called from the matching `Activity.onDestroy()` or
     * `ViewModel.onCleared()` to avoid leaking the receiver across
     * configuration changes.
     *
     * The unregister call swallows [IllegalArgumentException] thrown when
     * the receiver was never registered (e.g. when the user navigated
     * away before [register] ran), making this safe to call
     * unconditionally during teardown.
     */
    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (_: IllegalArgumentException) {
            // Already unregistered — nothing to do.
        }
    }

    companion object {
        private const val TAG = "BondLossReceiver"

        /**
         * Fully-qualified action name for `BluetoothDevice.ACTION_KEY_MISSING`.
         *
         * Hard-coded as a string literal so the codebase still compiles
         * against API 35 SDK jars (the constant is only declared in API
         * 36). At runtime the OS broadcasts the matching intent regardless.
         */
        const val ACTION_KEY_MISSING: String =
            "android.bluetooth.device.action.KEY_MISSING"

        /**
         * Fully-qualified action name for `BluetoothDevice.ACTION_ENCRYPTION_CHANGE`.
         *
         * Same compatibility rationale as [ACTION_KEY_MISSING].
         */
        const val ACTION_ENCRYPTION_CHANGE: String =
            "android.bluetooth.device.action.ENCRYPTION_CHANGE"
    }
}

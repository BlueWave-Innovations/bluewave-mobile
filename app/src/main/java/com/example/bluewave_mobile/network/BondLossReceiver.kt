package com.example.bluewave_mobile.network

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.example.bluewave_mobile.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that handles **Android 16** bond / encryption events.
 *
 * Android 16 introduced "Improved bond loss handling": when the local
 * adapter detects that the encryption keys for a previously-bonded peer
 * are missing, it no longer silently removes the bond. Instead it:
 *
 *  1. keeps the bond metadata so the user can re-pair without going
 *     through the full pairing dialog again;
 *  2. tears down the active ACL connection;
 *  3. broadcasts `BluetoothDevice.ACTION_KEY_MISSING` (this step), and
 *     later `BluetoothDevice.ACTION_ENCRYPTION_CHANGE` once a valid key
 *     is re-established (handled in step 31).
 *
 * On `ACTION_KEY_MISSING` we MUST NOT call any
 * `removeBond()`-style API — the OS already preserves the metadata for
 * us. Instead we tell the [MessageRepository] to pause traffic for that
 * peer; transmission resumes once the encryption key is back.
 */
class BondLossReceiver(
    private val repository: MessageRepository,
    private val scope: CoroutineScope
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_KEY_MISSING) return

        val device: BluetoothDevice? =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val mac = device?.address ?: run {
            Log.w(TAG, "$action received without EXTRA_DEVICE")
            return
        }

        Log.i(TAG, "ACTION_KEY_MISSING for $mac — pausing network operations")
        scope.launch {
            repository.pauseNetworkOperations(mac)
        }
    }

    /**
     * Convenience method: registers this receiver against the standard
     * `ACTION_KEY_MISSING` intent filter on the supplied [context].
     */
    fun register(context: Context) {
        val filter = IntentFilter(ACTION_KEY_MISSING)
        context.registerReceiver(this, filter)
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
    }
}

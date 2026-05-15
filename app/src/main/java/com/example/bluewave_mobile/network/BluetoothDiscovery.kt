package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.utils.parcelableExtra
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Reactive wrapper around the Android classic Bluetooth discovery API.
 *
 * Discovery is started via [BluetoothAdapter.startDiscovery] and the
 * resulting `ACTION_FOUND` broadcasts are observed through a
 * [BroadcastReceiver]. [callbackFlow] turns these one-shot callbacks into a
 * cold reactive stream — when the consumer cancels the [Flow], the receiver
 * is unregistered and discovery is cancelled, which is critical for battery
 * savings on the radio chipset.
 *
 * The class assumes that the caller has already verified the Bluetooth
 * runtime permissions via [PermissionManager.hasAllBluetoothPermissions]
 * and that the local adapter is enabled.
 */
class BluetoothDiscovery(
    private val context: Context,
    private val adapter: BluetoothAdapter?
) {

    /**
     * Returns a cold [Flow] that emits every distinct [BluetoothDeviceInfo]
     * seen on the radio while collected. The flow:
     *
     *  * registers a [BroadcastReceiver] for [BluetoothDevice.ACTION_FOUND]
     *    and adapter state intents on subscribe;
     *  * starts a fresh discovery cycle on subscribe;
     *  * cancels discovery and unregisters the receiver on cancellation
     *    (delegated to [awaitClose]).
     *
     * Devices whose system name is `null` are emitted with their MAC
     * address as a fallback display name.
     */
    @SuppressLint("MissingPermission")
    fun discoverDevices(): Flow<BluetoothDeviceInfo> = callbackFlow {
        val localAdapter = adapter
            ?: throw IllegalStateException("BluetoothAdapter is unavailable on this device")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            intent.parcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            val name = it.name ?: it.address
                            val rssi = intent.getShortExtra(
                                BluetoothDevice.EXTRA_RSSI,
                                Short.MIN_VALUE,
                            ).takeIf { v -> v != Short.MIN_VALUE }
                            trySend(
                                BluetoothDeviceInfo(
                                    name = name,
                                    macAddress = it.address,
                                    isPaired = false,
                                    rssi = rssi,
                                )
                            )
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Discovery cycles last ~12s on most chipsets; we don't
                        // auto-restart here — the caller is in charge of that.
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Cancel any in-flight discovery before kicking a new one off.
        if (localAdapter.isDiscovering) {
            localAdapter.cancelDiscovery()
        }
        localAdapter.startDiscovery()

        awaitClose {
            try {
                localAdapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // Permission may be revoked while discovery was running.
            }
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver might already be unregistered if registration failed.
            }
        }
    }

    /**
     * Returns the snapshot of devices already paired (bonded) with the
     * local adapter, useful for showing a "known devices" section in the UI
     * without waiting for a fresh discovery cycle.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDeviceInfo> {
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded.map { device ->
            BluetoothDeviceInfo(
                name = device.name ?: device.address,
                macAddress = device.address,
                isPaired = true
            )
        }
    }
}

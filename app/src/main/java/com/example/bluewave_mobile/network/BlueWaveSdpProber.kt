package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves "does this Bluetooth peer run BlueWave?" by inspecting the
 * SDP record advertised by the remote device.
 *
 * Workflow:
 *
 *  1. The caller (`DeviceListViewModel`) invokes [probe] for every
 *     newly-discovered peer.
 *  2. [probe] kicks off the asynchronous
 *     [BluetoothDevice.fetchUuidsWithSdp] call which, on success,
 *     causes the system to broadcast
 *     [BluetoothDevice.ACTION_UUID] containing the list of UUIDs the
 *     peer's SDP server published.
 *  3. The internal [BroadcastReceiver] catches that broadcast, checks
 *     whether [BluetoothConstants.APP_UUID] is in the published list
 *     and updates [appPresence] accordingly.
 *  4. To prevent the device-list UI from sitting forever on the
 *     optimistic "probe still pending" fallback (Android's SDP
 *     cache can drop an inquiry on the floor when the peer is out
 *     of range or refused the probe), every [probe] schedules a
 *     fallback coroutine that commits the entry to `false` after
 *     [BluetoothConstants.SDP_PROBE_TIMEOUT_MS] if the broadcast
 *     never lands. A late `ACTION_UUID` can still flip the entry
 *     back to `true` — the receiver-driven path remains
 *     authoritative.
 *
 * UI consumes [appPresence] reactively via Flow and decides whether a
 * peer should land in the "Can start chat" section (BlueWave detected)
 * or in the "No app yet" section (BlueWave **not** detected — show
 * the install CTA).
 *
 * The receiver is registered with [Context.RECEIVER_EXPORTED] because
 * `ACTION_UUID` is dispatched from the system Bluetooth process; an
 * unexported registration would silently never fire on Android 13+.
 */
class BlueWaveSdpProber(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val probeTimeoutMs: Long = BluetoothConstants.SDP_PROBE_TIMEOUT_MS,
) {

    private val _appPresence: MutableStateFlow<Map<String, Boolean>> =
        MutableStateFlow(emptyMap())

    /**
     * Reactive map `MAC (uppercase) → has-BlueWave-uuid`. Absence
     * means "not probed yet"; consumers should treat that as
     * "unknown" rather than "no app". After
     * [BluetoothConstants.SDP_PROBE_TIMEOUT_MS] the entry is
     * guaranteed to be present (committed to `false` when the
     * broadcast never lands).
     */
    val appPresence: StateFlow<Map<String, Boolean>> = _appPresence

    /**
     * Tracks the in-flight timeout coroutine for each MAC so a
     * back-to-back call to [probe] doesn't spawn duplicate jobs
     * (which would race when committing `false`).
     */
    private val pendingTimeouts: MutableMap<String, Job> = ConcurrentHashMap()

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_UUID) return
            val device: BluetoothDevice = intent
                .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val parcelUuids: Array<Parcelable>? =
                intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
            val hasOurUuid: Boolean = parcelUuids?.any { p ->
                (p as? ParcelUuid)?.uuid == BluetoothConstants.APP_UUID
            } ?: false
            val mac = device.address?.uppercase() ?: return
            // A definitive answer from the platform supersedes the
            // pending timeout — cancel it so we don't overwrite a
            // late "yes, BlueWave" with a stale "false".
            pendingTimeouts.remove(mac)?.cancel()
            _appPresence.update { current -> current + (mac to hasOurUuid) }
        }
    }

    @Volatile
    private var registered: Boolean = false

    /** Registers the system receiver. Idempotent. */
    fun start() {
        if (registered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        // The system Bluetooth stack is the broadcast sender, so the
        // receiver MUST be exported on Android 13+.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        registered = true
    }

    /** Unregisters the system receiver. Idempotent. */
    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was already unregistered", e)
        } finally {
            registered = false
        }
        // Cancel any in-flight timeouts so the prober teardown is
        // total — otherwise a timeout coroutine could fire after
        // teardown and dirty `_appPresence` with stale data.
        pendingTimeouts.values.forEach { it.cancel() }
        pendingTimeouts.clear()
    }

    /**
     * Hard signal: we just opened a live RFCOMM session to this
     * MAC, so the peer must be running BlueWave regardless of what
     * the cached SDP record says. The receiver-driven path is
     * still authoritative — once a fresh `ACTION_UUID` lands it can
     * flip the entry to `false` if the user uninstalled — but
     * until that happens the row should sit in "Можно написать"
     * instead of "Без приложения". This fixes the asymmetry where
     * one phone sees the other as a chat (because messages flowed
     * through fine) but the second phone still shows
     * "install suggestion" because its SDP cache for the first
     * phone was populated *before* the first phone's accept loop
     * came online and never invalidated.
     */
    fun markPresent(macAddress: String) {
        val mac = macAddress.uppercase()
        // A live RFCOMM session is the strongest possible signal
        // — cancel any pending fallback timeout for this MAC so we
        // don't end up overwriting a true with a false.
        pendingTimeouts.remove(mac)?.cancel()
        _appPresence.update { current ->
            if (current[mac] == true) current else current + (mac to true)
        }
    }

    /**
     * Asks the system to (re-)fetch the SDP record for [device] and
     * deliver the result through `ACTION_UUID`. Cheap when the SDP
     * cache already holds the record; the manager debounces repeat
     * probes for the same MAC by reusing the in-memory result.
     *
     * On every call we also arm a single fallback coroutine: if
     * [BluetoothConstants.SDP_PROBE_TIMEOUT_MS] elapses without a
     * matching `ACTION_UUID` broadcast, the entry is committed to
     * `false`. A late broadcast can still flip the entry back to
     * `true` — strictly upgrading from "no app" to "has app" is
     * always safe.
     */
    @SuppressLint("MissingPermission")
    fun probe(device: BluetoothDevice) {
        val mac = device.address?.uppercase() ?: return
        // `fetchUuidsWithSdp` is asynchronous; the result lands via the
        // ACTION_UUID broadcast handled above. Guarding against
        // SecurityException here keeps the call safe even if
        // BLUETOOTH_CONNECT was revoked between adapter discovery and
        // the SDP query landing.
        try {
            device.fetchUuidsWithSdp()
        } catch (e: SecurityException) {
            Log.w(TAG, "fetchUuidsWithSdp denied for ${device.address}", e)
            // Permission gone → we can't tell whether the peer runs
            // BlueWave or not, so leave the entry untouched. The UI
            // permission gate will block any flow that needs SDP
            // anyway.
            return
        }
        scheduleTimeout(mac)
    }

    /**
     * Convenience overload that resolves the [BluetoothDevice] from
     * its MAC address through the local [BluetoothAdapter] and then
     * defers to [probe]. Returns silently when the adapter is null
     * (emulator) or the MAC is malformed.
     */
    @SuppressLint("MissingPermission")
    fun probe(macAddress: String) {
        val localAdapter = adapter ?: return
        val device = try {
            localAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Refusing to probe invalid MAC '$macAddress'", e)
            return
        }
        probe(device)
    }

    /**
     * Arms a single deferred "commit to `false`" job for [mac] so the
     * UI never sits forever on the optimistic "probe pending" path.
     * If a job is already pending we leave it alone — duplicate
     * timeouts would race with each other and flap the entry.
     */
    private fun scheduleTimeout(mac: String) {
        if (pendingTimeouts.containsKey(mac)) return
        val job = scope.launch {
            try {
                delay(probeTimeoutMs)
                _appPresence.update { current ->
                    // Only fill in the entry if the broadcast never
                    // arrived. A late `ACTION_UUID` (true) MUST win
                    // over our timeout-driven `false` — and once
                    // any value is set, the receiver path owns
                    // future transitions.
                    if (current.containsKey(mac)) current else current + (mac to false)
                }
            } finally {
                pendingTimeouts.remove(mac)
            }
        }
        pendingTimeouts[mac] = job
    }

    private companion object {
        const val TAG = "BlueWaveSdpProber"
    }
}

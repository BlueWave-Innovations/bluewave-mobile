package com.example.bluewave_mobile

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.bluewave_mobile.di.AppContainer
import com.example.bluewave_mobile.network.BluetoothConstants
import com.example.bluewave_mobile.network.BlueWaveBluetoothService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application subclass that owns the process-wide [AppContainer].
 *
 * The container is instantiated exactly once per process in
 * [onCreate] and exposed as a public property so [MainActivity] /
 * ViewModelProviders can pull singletons without going through a
 * static `getInstance()` anti-pattern.
 *
 * In addition to the container, [onCreate] launches the network
 * plumbing that ties the radio to the Room database:
 *
 *  * [com.example.bluewave_mobile.network.BluetoothSessionManager.start]
 *    opens the long-lived RFCOMM server socket and starts the accept
 *    loop, so every BlueWave install on the device is automatically
 *    reachable as soon as the app process is alive;
 *  * a single application-scoped collector forwards every
 *    [com.example.bluewave_mobile.network.IncomingPeerMessage] into
 *    [com.example.bluewave_mobile.data.MessageRepository.processIncomingMessage],
 *    which encrypts the plaintext for at-rest storage and lets the UI
 *    update through Room's invalidation tracker.
 *
 * Registered through `<application android:name=".BlueWaveApplication" />`
 * in `AndroidManifest.xml`.
 */
class BlueWaveApplication : Application() {

    /**
     * Process-wide DI container. Initialised on [onCreate] before any
     * Activity callback fires, so accessing it from `MainActivity` is
     * always safe.
     */
    lateinit var container: AppContainer
        private set

    /**
     * Application-scoped coroutine scope used for plumbing that must
     * outlive any single Activity (e.g. the inbound-message pump).
     * Backed by a [SupervisorJob] so an exception in one collector
     * does not bring down the others.
     */
    private val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in application scope", throwable)
        },
    )

    /**
     * Receiver that listens for `BluetoothAdapter.ACTION_STATE_CHANGED`
     * so we can re-fire the auto-connect fan-out the instant the user
     * (or the system) flips the radio back on. Without it, a user
     * who toggles BT off and then back on while the app is in the
     * foreground would have to manually tap each contact to wake up
     * the per-peer RFCOMM session.
     *
     * The receiver is registered unconditionally on [onCreate] and is
     * not unregistered — the application process holds the singleton
     * for its entire lifetime, so an `onTerminate` unregister is the
     * only correct teardown and `onTerminate` only fires on emulators.
     */
    private val bluetoothStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val newState = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR,
            )
            if (newState != BluetoothAdapter.STATE_ON) return
            // Give the adapter a beat to finish exposing the SDP
            // service record before we hammer it with outbound
            // connects — same reasoning as `AUTO_CONNECT_INITIAL_DELAY_MS`
            // on cold launch.
            applicationScope.launch {
                delay(AUTO_CONNECT_INITIAL_DELAY_MS)
                connectToKnownPeers()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Start the RFCOMM accept loop unconditionally on cold launch:
        //  * already-paired peers MUST be able to reach us over RFCOMM
        //    regardless of the "Bluetooth visibility" setting (the
        //    visibility toggle is an Android *discoverable* concept —
        //    it only controls whether we appear in fresh inquiry scans
        //    done by **unpaired** peers, not whether we accept inbound
        //    RFCOMM connections from peers that already know our MAC);
        //  * registering our service UUID through
        //    `listenUsingRfcommWithServiceRecord` is also what
        //    populates the local SDP database — if we never call it,
        //    a remote peer's `fetchUuidsWithSdp()` returns "no
        //    BlueWave on this device" and the row falls to the
        //    install-suggestion section even though the app is
        //    installed.
        //
        // Both [start] and [shutdown] are idempotent, so process
        // restarts and Activity recreations are safe.
        container.bluetoothSessionManager.start()

        // Register the SDP record receiver up-front so the device-list
        // screen can probe peers as soon as the user taps "Scan".
        container.sdpProber.start()

        // Bring the foreground service online so Android stops
        // reclaiming the process between user sessions. The service
        // body doesn't own any state — the accept-loop, SDP
        // receiver and the auto-reconnect machinery already live
        // on `applicationScope` — it exists purely to anchor the
        // persistent "BlueWave — listening for messages"
        // notification that tells the OS "don't kill this process".
        // Without the FGS Android 13+ will tear the process down
        // a few minutes after the last visible activity goes away
        // and inbound RFCOMM connections start silently failing.
        BlueWaveBluetoothService.start(this)

        // Seed the built-in chat folders ("Work" / "Family") on
        // first launch so the chip row above the device list has
        // something to render. Idempotent — subsequent launches
        // are a single COUNT query that returns early.
        applicationScope.launch {
            runCatching {
                container.folderRepository.seedBuiltInsIfNeeded()
            }.onFailure { e ->
                Log.w(TAG, "Failed to seed built-in folders", e)
            }
        }

        // Stage the running APK in the cache so `ApkSender.suggestInstall`
        // has a FileProvider URI ready when the user taps the
        // "Send via Bluetooth" CTA on a no-app peer. Cheap I/O on the
        // app-private cache directory; safe to run on the main thread
        // for hackathon-scale binaries.
        runCatching { container.apkSender.stageApk() }
            .onFailure { e -> Log.w(TAG, "APK staging failed at process start", e) }

        // Pump every framed payload received from any peer into the
        // repository. The repository owns at-rest encryption, dedupe
        // (via the database) and wakes the UI through Flow.
        applicationScope.launch {
            container.bluetoothSessionManager.incoming.collect { incoming ->
                runCatching {
                    container.messageRepository.processIncomingMessage(
                        macAddress = incoming.macAddress,
                        senderName = incoming.deviceName,
                        rawData = incoming.payload,
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Failed to persist incoming message from ${incoming.macAddress}", e)
                }
            }
        }

        // Drive the symmetric libsignal X3DH handshake: every fresh
        // RFCOMM session — whether we initiated the connect or the
        // accept loop produced it — triggers the repository to push
        // its local key bundle to the peer. The repository keeps a
        // per-peer "already sent" guard so we never duplicate the
        // bundle within a session. We also flip the SDP-derived
        // presence map to `true` for this MAC: a live RFCOMM
        // session is the strongest possible signal that the peer
        // runs BlueWave, regardless of what the platform's SDP
        // cache happens to say. This is the fix for the asymmetric
        // chat / install-suggestion rows we saw on-device when one
        // phone's SDP cache for the other was populated *before*
        // that other phone's accept loop came online.
        applicationScope.launch {
            container.bluetoothSessionManager.sessionAttached.collect { mac ->
                runCatching {
                    container.sdpProber.markPresent(mac)
                }.onFailure { e ->
                    Log.w(TAG, "markPresent failed for $mac", e)
                }
                runCatching {
                    container.messageRepository.onPeerLinkUp(mac)
                }.onFailure { e ->
                    Log.w(TAG, "onPeerLinkUp failed for $mac", e)
                }
            }
        }

        // Mirror the per-peer link teardown: when the session manager
        // evicts an RFCOMM session (peer turned BT off, killed the
        // app, walked out of range and tripped the liveness watchdog,
        // …) the repository drops its libsignal session record and
        // re-arms the "already shipped key bundle" guard so the very
        // next reconnect rebuilds the Double Ratchet from scratch.
        // Without this hook a peer reinstall or process kill would
        // leave both sides convinced that the session is still live
        // and every subsequent decrypt would silently fail.
        applicationScope.launch {
            container.bluetoothSessionManager.sessionDetached.collect { mac ->
                runCatching {
                    container.messageRepository.onPeerLinkDown(mac)
                }.onFailure { e ->
                    Log.w(TAG, "onPeerLinkDown failed for $mac", e)
                }
            }
        }

        // Re-broadcast the local profile card to every peer with a
        // SECURE Signal session whenever the user edits their name /
        // handle / bio / avatar in the Profile tab. `drop(1)` skips
        // the initial DataStore replay so we don't push on cold
        // launch — the per-session push driven by `onPeerLinkUp` is
        // sufficient for fresh sessions.
        applicationScope.launch {
            container.userPreferencesRepository.localProfile
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    runCatching {
                        container.messageRepository.onLocalProfileChanged()
                    }.onFailure { e ->
                        Log.w(TAG, "onLocalProfileChanged failed", e)
                    }
                }
        }

        // Eager auto-connect on cold launch + on every link teardown.
        // RFCOMM sessions are otherwise lazily created the first time
        // the user taps a contact and sends a message, which means
        // the "online dot" stays grey on both phones even when both
        // apps are running side by side with their accept loops
        // waiting. To match the "open the app and you're already
        // online with everyone you know" UX of mainstream messengers
        // we proactively fan out outbound connect attempts to every
        // already-bonded device a few hundred ms after the accept
        // loop comes up. The race between simultaneous outbound and
        // inbound connects is handled in `attachSession`, which
        // evicts the older session if two ever land for the same MAC.
        //
        // The first attempt is delayed by `AUTO_CONNECT_INITIAL_DELAY_MS`
        // so the accept-loop has time to register the SDP service
        // record on both sides; otherwise the very first connect can
        // race the listen-side SDP record and fail with "service
        // discovery failed".
        applicationScope.launch {
            delay(AUTO_CONNECT_INITIAL_DELAY_MS)
            connectToKnownPeers()
        }

        // Re-fire the auto-connect fan-out the moment the adapter
        // flips back on after the user toggled it off (or after a
        // transient driver glitch). The accept loop in
        // [BluetoothSessionManager.acceptLoop] already recovers on
        // its own — this is purely for the outbound side so two
        // phones rediscover each other instantly without the user
        // having to tap into a chat.
        // ACTION_STATE_CHANGED is dispatched by the platform
        // Bluetooth process, so the receiver MUST be exported on
        // Android 13+ — an unexported registration would silently
        // never fire and the auto-reconnect fan-out would stall
        // after every adapter on/off flip.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Re-run the auto-connect probe whenever a session is
        // detached (peer toggled BT, killed the app, …) so the link
        // re-establishes itself the moment the peer comes back, no
        // user interaction required. We sleep `AUTO_RECONNECT_BACKOFF_MS`
        // to give the peer's adapter time to settle before
        // re-attempting; a still-unreachable peer just fails the
        // connect() call and the next sessionDetached → reconnect
        // edge will fire when something else brings the link up.
        applicationScope.launch {
            container.bluetoothSessionManager.sessionDetached.collect { mac ->
                delay(AUTO_RECONNECT_BACKOFF_MS)
                runCatching {
                    container.bluetoothSessionManager.connect(mac)
                }.onFailure { e ->
                    Log.w(TAG, "auto-reconnect attempt failed for $mac", e)
                }
            }
        }

        // NOTE: the user-facing "Bluetooth visibility" selector in the
        // Settings screen only controls the Android *discoverable*
        // window via `ACTION_REQUEST_DISCOVERABLE` — it does NOT
        // start or stop the RFCOMM accept-loop. That accept-loop is
        // always running (see the unconditional `start()` call
        // above) so paired peers can keep messaging us and so our
        // SDP record stays advertised. Settings screen handles the
        // discoverable-intent dispatch directly when the user picks
        // a non-OFF duration.
    }

    /**
     * Fires a non-blocking outbound `connect()` attempt against every
     * known peer:
     *
     *  * **Bonded devices** — picked from `BluetoothAdapter.bondedDevices`.
     *    Catches anyone we've ever paired with at the system level,
     *    including the no-pairing era because Android occasionally
     *    bonds opportunistically.
     *  * **Chat-history peers** — every MAC we have at least one
     *    persisted message for. This is the channel the user cares
     *    about most: once two people have talked once, the next cold
     *    launch reconnects them without any tap.
     *
     * The session manager dedupes inbound + outbound connects per
     * MAC so the worst case is a racing accept on the peer side that
     * gets evicted as soon as our outbound socket lands.
     *
     * Peers that do not run BlueWave (e.g. bonded headphones or smart
     * watches) fail with `IOException` inside `connect()` and are
     * logged at DEBUG once — no further retries. Unbonded peers
     * discovered through scanning are picked up by
     * `DeviceListViewModel`, which calls `connect()` directly when
     * the user taps the row.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectToKnownPeers() {
        val adapter = container.bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val targets: MutableSet<String> = HashSet()

        // 1) bonded devices
        try {
            adapter.bondedDevices?.forEach { device ->
                device.address?.takeIf { it.isNotBlank() }?.let { targets.add(it.uppercase()) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied by permissions; skipping bonded auto-connect", e)
        }

        // 2) chat-history peers — every MAC with at least one
        // persisted message gets a connect attempt.
        runCatching {
            container.messageDao.getLatestMessagePerDevice().first().forEach { row ->
                row.macAddress.takeIf { it.isNotBlank() }?.let { targets.add(it.uppercase()) }
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to enumerate chat-history peers for auto-connect", e)
        }

        if (targets.isEmpty()) return

        for (mac in targets) {
            applicationScope.launch {
                runCatching {
                    container.bluetoothSessionManager.connect(mac)
                }.onFailure { e ->
                    Log.d(TAG, "auto-connect skipped for $mac: ${e.message}")
                }
            }
        }
    }

    override fun onTerminate() {
        // onTerminate() only fires on the emulator but tearing the
        // session manager down is cheap and idempotent — guarantees
        // no leaked server sockets when the platform actually invokes
        // it.
        runCatching { container.bluetoothSessionManager.shutdown() }
        runCatching { container.sdpProber.stop() }
        super.onTerminate()
    }

    private companion object {
        const val TAG = "BlueWaveApplication"

        /**
         * Grace period before the very first auto-connect fan-out so
         * the local accept loop has time to register its SDP service
         * record. Without it, the first outbound connect can race
         * the listener and fail with "service discovery failed".
         */
        const val AUTO_CONNECT_INITIAL_DELAY_MS: Long = 500L

        /**
         * Cool-down between a session detaching and the next
         * outbound connect attempt against the same MAC. Matches
         * roughly two heartbeat intervals so the peer's adapter has
         * time to settle if the detach was caused by a transient
         * radio glitch.
         */
        const val AUTO_RECONNECT_BACKOFF_MS: Long = BluetoothConstants.HEARTBEAT_INTERVAL_MS * 2
    }
}

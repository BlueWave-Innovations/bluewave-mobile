package com.example.bluewave_mobile

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.bluewave_mobile.utils.BlueWaveLogger
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.example.bluewave_mobile.di.AppContainer
import com.example.bluewave_mobile.network.BluetoothConstants
import com.example.bluewave_mobile.service.BluetoothForegroundService
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
class BlueWaveApplication : Application(), ImageLoaderFactory {

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
            BlueWaveLogger.e(TAG, "Uncaught exception in application scope", throwable)
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
        BlueWaveLogger.init(this)
        registerActivityLifecycleCallbacks(AppLifecycleLogger())
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

        // Seed the built-in chat folders ("Work" / "Family") on
        // first launch so the chip row above the device list has
        // something to render. Idempotent — subsequent launches
        // are a single COUNT query that returns early.
        applicationScope.launch {
            runCatching {
                container.folderRepository.seedBuiltInsIfNeeded()
            }.onFailure { e ->
                BlueWaveLogger.w(TAG, "Failed to seed built-in folders", e)
            }
        }

        // Stage the running APK in the cache so `ApkSender.suggestInstall`
        // has a FileProvider URI ready when the user taps the
        // "Send via Bluetooth" CTA on a no-app peer. Runs off the main
        // thread to avoid ANR on large APKs or slow storage.
        applicationScope.launch {
            runCatching { container.apkSender.stageApk() }
                .onFailure { e -> BlueWaveLogger.w(TAG, "APK staging failed at process start", e) }
        }

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
                    BlueWaveLogger.w(TAG, "Failed to persist incoming message from ${incoming.macAddress}", e)
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
                    BlueWaveLogger.w(TAG, "markPresent failed for $mac", e)
                }
                runCatching {
                    container.messageRepository.onPeerLinkUp(mac)
                }.onFailure { e ->
                    BlueWaveLogger.w(TAG, "onPeerLinkUp failed for $mac", e)
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
                    BlueWaveLogger.w(TAG, "onPeerLinkDown failed for $mac", e)
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
                        BlueWaveLogger.w(TAG, "onLocalProfileChanged failed", e)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        }

        // Re-run the auto-connect probe whenever a session is
        // detached (peer toggled BT, killed the app, …) so the link
        // re-establishes itself the moment the peer comes back, no
        // user interaction required. We retry up to three times with
        // exponential backoff so a transient radio glitch does not
        // permanently strand the conversation.
        applicationScope.launch {
            container.bluetoothSessionManager.sessionDetached.collect { mac ->
                val backoffDelays = listOf(
                    AUTO_RECONNECT_BACKOFF_MS,
                    AUTO_RECONNECT_BACKOFF_MS * 2,
                    AUTO_RECONNECT_BACKOFF_MS * 4,
                )
                for ((attempt, delayMs) in backoffDelays.withIndex()) {
                    delay(delayMs)
                    if (container.bluetoothSessionManager.isConnected(mac)) break
                    val success = runCatching {
                        container.bluetoothSessionManager.connect(mac)
                        container.bluetoothSessionManager.isConnected(mac)
                    }.getOrDefault(false)
                    if (success) {
                        BlueWaveLogger.i(TAG, "auto-reconnect succeeded for $mac on attempt ${attempt + 1}")
                        break
                    } else {
                        BlueWaveLogger.w(TAG, "auto-reconnect attempt ${attempt + 1} failed for $mac")
                    }
                }
            }
        }

        // Periodic health-check: every 15 seconds we scan the known-peer
        // list and attempt to connect to anyone who is not currently
        // online. This keeps the "users are online for each other" UX
        // even when both phones sit in a pocket with the screen off.
        applicationScope.launch {
            while (true) {
                delay(AUTO_CONNECT_PERIODIC_INTERVAL_MS)
                val adapter = container.bluetoothAdapter
                if (adapter?.isEnabled != true) continue
                connectToKnownPeers(onlyMissing = true)
            }
        }

        // Periodic profile re-sync: every 60 seconds we re-send the
        // local profile to any connected peer whose PROFILE_ACK is
        // stale. This guarantees eventual delivery even when a
        // PROFILE_METADATA frame is dropped due to Bluetooth buffer
        // overflow or a transient disconnect.
        applicationScope.launch {
            while (true) {
                delay(PROFILE_SYNC_INTERVAL_MS)
                runCatching {
                    container.messageRepository.syncProfilesToConnectedPeers()
                }.onFailure { e ->
                    BlueWaveLogger.w(TAG, "Periodic profile sync failed", e)
                }
            }
        }

        // Start the foreground service so Bluetooth stays active in
        // the background and message notifications are posted.
        BluetoothForegroundService.start(this)

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
     * known peer.
     *
     *  * **Bonded devices** — picked from `BluetoothAdapter.bondedDevices`.
     *  * **Chat-history peers** — every MAC with at least one persisted
     *    message.
     *
     * When [onlyMissing] is `true` (the periodic health-check path) we
     * skip peers that already have an active session, avoiding useless
     * socket churn. When `false` (cold-launch / BT-toggle fan-out) we
     * hit everyone because the caller wants a full refresh.
     *
     * The session manager dedupes inbound + outbound connects per MAC.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectToKnownPeers(onlyMissing: Boolean = false) {
        val adapter = container.bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val targets: MutableSet<String> = HashSet()

        // 1) bonded devices
        try {
            adapter.bondedDevices?.forEach { device ->
                device.address?.takeIf { it.isNotBlank() }?.let { targets.add(it.uppercase()) }
            }
        } catch (e: SecurityException) {
            BlueWaveLogger.w(TAG, "bondedDevices denied by permissions; skipping bonded auto-connect", e)
        }

        // 2) chat-history peers
        runCatching {
            container.messageDao.getLatestMessagePerDevice().first().forEach { row ->
                row.macAddress.takeIf { it.isNotBlank() }?.let { targets.add(it.uppercase()) }
            }
        }.onFailure { e ->
            BlueWaveLogger.w(TAG, "Failed to enumerate chat-history peers for auto-connect", e)
        }

        if (targets.isEmpty()) return

        for (mac in targets) {
            if (onlyMissing && container.bluetoothSessionManager.isConnected(mac)) continue
            applicationScope.launch {
                runCatching {
                    container.bluetoothSessionManager.connect(mac)
                }.onFailure { e ->
                    BlueWaveLogger.d(TAG, "auto-connect skipped for $mac: ${e.message}")
                }
            }
        }
    }

    /**
     * Public trigger for the auto-connect fan-out, used by the
     * foreground service when the system restarts it so we don't
     * wait for the next periodic tick to re-establish sessions.
     */
    fun triggerAutoConnect() {
        applicationScope.launch {
            connectToKnownPeers(onlyMissing = true)
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .build()
            }
            .build()

    private class AppLifecycleLogger : ActivityLifecycleCallbacks {
        private fun log(state: String, activity: android.app.Activity) {
            BlueWaveLogger.d("Lifecycle", "$state: ${activity.javaClass.simpleName}")
        }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) =
            log("onCreate", activity)
        override fun onActivityStarted(activity: android.app.Activity) = log("onStart", activity)
        override fun onActivityResumed(activity: android.app.Activity) = log("onResume", activity)
        override fun onActivityPaused(activity: android.app.Activity) = log("onPause", activity)
        override fun onActivityStopped(activity: android.app.Activity) = log("onStop", activity)
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = log("onDestroy", activity)
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

        /**
         * Interval between periodic health-check scans of the
         * known-peer list. Every tick we attempt to connect to any
         * peer that does not currently have an active RFCOMM session,
         * ensuring the "always online" UX without waiting for a user
         * interaction or a session-detach event.
         */
        const val AUTO_CONNECT_PERIODIC_INTERVAL_MS: Long = 15_000L

        /**
         * Interval between periodic profile re-sync attempts.
         * Peers who have not yet ACKed the latest profile version
         * receive a re-send so that missed frames are eventually
         * delivered.
         */
        const val PROFILE_SYNC_INTERVAL_MS: Long = 60_000L
    }
}

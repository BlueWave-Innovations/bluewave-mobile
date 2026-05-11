package com.example.bluewave_mobile

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import com.example.bluewave_mobile.di.AppContainer
import com.example.bluewave_mobile.network.BluetoothConstants
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
            connectToBondedPeers()
        }

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
     * device the platform reports as already bonded. The session
     * manager dedupes inbound + outbound connects per MAC so the
     * worst case is a racing accept on the peer side that gets
     * evicted as soon as our outbound socket lands.
     *
     * Bonded devices that do not run BlueWave fail with `IOException`
     * inside `connect()` and are logged once — no further retries.
     * Unbonded peers are picked up by the scan flow in
     * `DeviceListViewModel`, which calls `connect()` directly when
     * the user opens the chat, so we deliberately do **not** spam
     * connect attempts to the entire inquiry result here.
     */
    @SuppressLint("MissingPermission")
    private suspend fun connectToBondedPeers() {
        val adapter = container.bluetoothAdapter ?: return
        if (!adapter.isEnabled) return
        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied by permissions; skipping auto-connect", e)
            return
        }
        if (bonded.isEmpty()) return
        for (device in bonded) {
            val mac = device.address ?: continue
            applicationScope.launch {
                runCatching {
                    container.bluetoothSessionManager.connect(mac)
                }.onFailure { e ->
                    // Failure here is normal for headphones, smart
                    // watches and any other bonded peer that does
                    // not run BlueWave — the SDP lookup misses our
                    // UUID, `createInsecureRfcommSocketToServiceRecord`
                    // throws, and we move on.
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

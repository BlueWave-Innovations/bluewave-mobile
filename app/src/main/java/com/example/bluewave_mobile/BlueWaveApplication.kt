package com.example.bluewave_mobile

import android.app.Application
import android.util.Log
import com.example.bluewave_mobile.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        // bundle within a session.
        applicationScope.launch {
            container.bluetoothSessionManager.sessionAttached.collect { mac ->
                runCatching {
                    container.messageRepository.onPeerLinkUp(mac)
                }.onFailure { e ->
                    Log.w(TAG, "onPeerLinkUp failed for $mac", e)
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
    }
}

package com.example.bluewave_mobile

import android.app.Application
import android.util.Log
import com.example.bluewave_mobile.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
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

        // Spin up the perpetual RFCOMM accept loop so every device
        // running BlueWave is automatically reachable. The session
        // manager itself defers all heavy work to Dispatchers.IO.
        container.bluetoothSessionManager.start()

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
    }

    override fun onTerminate() {
        // onTerminate() only fires on the emulator but tearing the
        // session manager down is cheap and idempotent — guarantees
        // no leaked server sockets when the platform actually invokes
        // it.
        runCatching { container.bluetoothSessionManager.shutdown() }
        super.onTerminate()
    }

    private companion object {
        const val TAG = "BlueWaveApplication"
    }
}

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
import kotlinx.coroutines.launch

/**
 * Application subclass that owns the process-wide [AppContainer].
 *
 * On [onCreate] the container is built once and the RFCOMM plumbing
 * is wired up: the accept loop opens its server socket, the SDP
 * prober subscribes to discovery broadcasts, the live APK is staged
 * for `ApkSender.suggestInstall`, every framed payload coming out of
 * the session manager is pushed through the repository, and a small
 * grace-delayed auto-connect fan-out fires outbound connect attempts
 * against every already-bonded peer so we don't have to wait for the
 * user to tap "send" before two BlueWave instances become reachable
 * to each other.
 */
class BlueWaveApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught exception in application scope", throwable)
        },
    )

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Open the long-lived RFCOMM server socket so every BlueWave
        // install is reachable as soon as the process is alive.
        container.bluetoothSessionManager.start()

        // Subscribe to system Bluetooth discovery broadcasts so the
        // device-list screen can probe peers for the BlueWave SDP
        // record without re-registering its own receiver.
        container.sdpProber.start()

        // Stage the running APK into the cache so the "Send via
        // Bluetooth" CTA on no-app rows has a FileProvider URI ready.
        runCatching { container.apkSender.stageApk() }
            .onFailure { e -> Log.w(TAG, "APK staging failed at process start", e) }

        // Pump every framed payload from any peer through the
        // repository (encrypt at rest, dedupe, notify UI via Room).
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

        // Eager auto-connect on cold launch. RFCOMM sessions are
        // otherwise only created the first time the user taps a
        // contact, which means two BlueWave phones sitting side by
        // side with their accept loops up still need a manual nudge
        // before the first message can flow. Firing outbound
        // connect attempts at every already-bonded device a few
        // hundred ms after start lets two installs converge to
        // "ready to chat" without user input. The session manager
        // already evicts the older session inside [attachSession]
        // when an outbound + inbound connect collide on the same
        // MAC, so a racing accept on the peer side is harmless.
        applicationScope.launch {
            delay(AUTO_CONNECT_INITIAL_DELAY_MS)
            connectToBondedPeers()
        }
    }

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
                    // Normal for bonded peers that don't run BlueWave
                    // (headphones, smartwatches, …) — SDP misses the
                    // service UUID and `connect()` returns.
                    Log.d(TAG, "auto-connect skipped for $mac: ${e.message}")
                }
            }
        }
    }

    override fun onTerminate() {
        runCatching { container.bluetoothSessionManager.shutdown() }
        runCatching { container.sdpProber.stop() }
        super.onTerminate()
    }

    private companion object {
        const val TAG = "BlueWaveApplication"

        /**
         * Grace period before the first auto-connect fan-out so the
         * local accept loop has time to register its SDP service
         * record. Without it, the first outbound connect can race
         * the listener and fail with "service discovery failed".
         */
        const val AUTO_CONNECT_INITIAL_DELAY_MS: Long = 500L

        /**
         * Reserved for a future "reconnect on detach" path once the
         * session manager exposes a per-peer detach signal. Currently
         * unused — kept for documentation of the intended cool-down.
         */
        @Suppress("unused")
        const val AUTO_RECONNECT_BACKOFF_MS: Long = BluetoothConstants.HEARTBEAT_INTERVAL_MS * 2
    }
}

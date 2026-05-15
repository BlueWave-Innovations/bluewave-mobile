package com.example.bluewave_mobile.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of [MessageTransport] backed by classic
 * Bluetooth RFCOMM sockets.
 *
 * Lifecycle owned by `BlueWaveApplication.applicationScope`:
 *
 *  * On [start] the manager opens a long-lived
 *    [BluetoothServerSocket] and runs an accept loop on
 *    [Dispatchers.IO]. Every accepted socket becomes a new
 *    [BluetoothSession] keyed by the peer's MAC address; if a session
 *    for that MAC already exists, the older one is cancelled to
 *    guarantee a single active session per peer.
 *  * On [connect] the manager opens an outbound
 *    [BluetoothSocket] for the chosen peer and, if the connect
 *    succeeds, attaches a session the same way. Calling [connect] on
 *    a peer that is already connected is a no-op.
 *  * On [send] the matching session frames the payload and writes it
 *    over the socket. A failed write or a closed read loop evicts
 *    the session, so a subsequent [connect] will start fresh.
 *
 * The [incoming] flow re-emits every fully-reassembled frame coming
 * out of any session, decorated with the originating peer's MAC and
 * friendly name.
 *
 * Concurrency model:
 *  * `sessions` is a [ConcurrentHashMap] for fast read-mostly
 *    lookups from the UI / repository.
 *  * Mutations of the map and side-effecting attach / disconnect
 *    operations are serialised through [sessionLock] so simultaneous
 *    accept + connect on the same peer cannot leak two live
 *    sessions.
 */
class BluetoothSessionManager(
    private val adapter: BluetoothAdapter?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MessageTransport {

    private val sessions: MutableMap<String, BluetoothSession> = ConcurrentHashMap()
    private val sessionLock: Mutex = Mutex()

    private val _incoming: MutableSharedFlow<IncomingPeerMessage> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<IncomingPeerMessage> = _incoming.asSharedFlow()

    private val _sessionAttached: MutableSharedFlow<String> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
    override val sessionAttached: Flow<String> = _sessionAttached.asSharedFlow()

    private val _sessionDetached: MutableSharedFlow<String> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 16)
    override val sessionDetached: Flow<String> = _sessionDetached.asSharedFlow()

    private val _connectedPeers: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val connectedPeers: Flow<Set<String>> = _connectedPeers.asStateFlow()

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private val startLock = Any()

    /**
     * Launches the perpetual accept loop. Calling [start] more than
     * once is a no-op so the application class can call it from
     * `onCreate` without worrying about activity recreations.
     */
    fun start() {
        synchronized(startLock) {
            if (acceptJob?.isActive == true) return
            acceptJob = scope.launch { acceptLoop() }
        }
    }

    /**
     * Tears down the accept loop, every live session and the
     * underlying server socket. Idempotent.
     */
    fun shutdown() {
        synchronized(startLock) {
            acceptJob?.cancel()
            acceptJob = null
        }
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            BlueWaveLogger.w(TAG, "Error closing server socket on shutdown", e)
        } finally {
            serverSocket = null
        }
        // Cancel all live sessions; onClosed will evict most of them.
        val snapshot = sessions.keys.toList()
        for (mac in snapshot) {
            sessions[mac]?.cancel()
        }
        // Forcibly clear any stragglers so isConnected() doesn't lie
        // after shutdown() returns.
        val remaining = sessions.keys.toList()
        for (mac in remaining) {
            sessions.remove(mac)
            _connectedPeers.update { current -> current - mac }
            runCatching { _sessionDetached.tryEmit(mac) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun acceptLoop() {
        val localAdapter = adapter ?: run {
            BlueWaveLogger.w(TAG, "BluetoothAdapter unavailable; accept loop will not start")
            return
        }
        // Outer loop: when the server socket dies (BT toggled off,
        // transient driver error, …) we wait a short back-off and try
        // again so the listener comes back online as soon as the
        // adapter does — without forcing the user to restart the app.
        // A `BLUETOOTH_CONNECT` permission failure is treated as
        // terminal: there is nothing we can recover from in this
        // process.
        while (scope.isActive) {
            if (!localAdapter.isEnabled) {
                delay(BluetoothConstants.ACCEPT_RETRY_DELAY_MS)
                continue
            }
            val socket: BluetoothServerSocket = try {
                // Insecure RFCOMM does NOT require the peer to be
                // bonded — we lean on libsignal E2EE for end-to-end
                // confidentiality, so the link-layer pairing
                // dialog is pure friction. Two BlueWave installs
                // can now exchange messages the moment both
                // adapters are powered on, no "Pair this device?"
                // popup required.
                localAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothConstants.SERVICE_NAME,
                    BluetoothConstants.APP_UUID,
                )
            } catch (e: IOException) {
                BlueWaveLogger.w(TAG, "Failed to open RFCOMM server socket: ${e.message}; retrying")
                delay(BluetoothConstants.ACCEPT_RETRY_DELAY_MS)
                continue
            } catch (e: SecurityException) {
                BlueWaveLogger.e(TAG, "BLUETOOTH_CONNECT permission missing for listen()", e)
                delay(BluetoothConstants.ACCEPT_RETRY_DELAY_MS)
                continue
            }
            serverSocket = socket

            try {
                while (scope.isActive) {
                    val accepted: BluetoothSocket = try {
                        withContext(Dispatchers.IO) { socket.accept() }
                    } catch (e: IOException) {
                        BlueWaveLogger.d(TAG, "Server socket closed: ${e.message}")
                        break
                    }
                    try {
                        attachSession(accepted)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        BlueWaveLogger.w(TAG, "attachSession failed for accepted socket", e)
                        try { accepted.close() } catch (_: IOException) { /* best effort */ }
                    }
                }
            } finally {
                try { socket.close() } catch (_: IOException) { /* already closed */ }
                serverSocket = null
            }
            // accept() returned an error — wait a moment before
            // re-listening so we don't burn CPU when the adapter is
            // half-up / half-down during a toggle.
            if (scope.isActive) {
                delay(BluetoothConstants.ACCEPT_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Inserts a freshly connected [BluetoothSocket] into the session
     * map under the peer's MAC, evicting any pre-existing session for
     * that peer first. Runs under [sessionLock] so simultaneous
     * accept + connect on the same MAC always converges on a single
     * live session.
     */
    private suspend fun attachSession(socket: BluetoothSocket) {
        val session = BluetoothSession(socket)
        val mac = session.remoteMacAddress
        if (mac.isBlank()) {
            BlueWaveLogger.w(TAG, "Refusing to attach session with empty MAC; closing socket")
            session.cancel()
            return
        }
        sessionLock.withLock {
            sessions.put(mac, session)?.cancel()
        }
        _connectedPeers.update { current -> current + mac }
        _sessionAttached.emit(mac)
        session.start(
            scope = scope,
            onFrame = { payload ->
                _incoming.emit(
                    IncomingPeerMessage(
                        macAddress = mac,
                        deviceName = session.remoteName,
                        payload = payload,
                    )
                )
            },
            onClosed = {
                val evicted: Boolean = sessionLock.withLock {
                    if (sessions[mac] === session) {
                        sessions.remove(mac)
                        _connectedPeers.update { current -> current - mac }
                        true
                    } else {
                        false
                    }
                }
                if (evicted) {
                    // Fire the detach hook outside the lock so
                    // collectors (e.g. the application-scope wiring
                    // that resets libsignal state for this peer) can
                    // perform suspending work without holding up
                    // future attach/detach plumbing.
                    // tryEmit is used because onClosed runs inside a
                    // finally block where a suspending emit could throw
                    // CancellationException and mask the real cause.
                    _sessionDetached.tryEmit(mac)
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(macAddress: String) {
        val key = macAddress.uppercase()
        sessions[key]?.let { return }
        val localAdapter = adapter ?: return
        val device = try {
            localAdapter.getRemoteDevice(macAddress)
        } catch (e: IllegalArgumentException) {
            BlueWaveLogger.w(TAG, "Invalid MAC '$macAddress'", e)
            return
        }

        // Discovery is heavyweight on the radio; cancel before we
        // attempt an outgoing connect or the connect() call may
        // stall or fail intermittently (Google's Bluetooth guide).
        try {
            if (localAdapter.isDiscovering) localAdapter.cancelDiscovery()
        } catch (e: Throwable) {
            BlueWaveLogger.w(TAG, "cancelDiscovery() failed", e)
        }

        // Symmetric with the accept side, which calls
        // `listenUsingInsecureRfcommWithServiceRecord` — both endpoints
        // must agree on the secure/insecure flag or the RFCOMM
        // negotiation fails and the connect throws IOException
        // ("read failed, socket might closed or timeout, read ret: -1"
        // on the kernel side). The previous secure call here was a
        // leftover from before the no-pairing switch and was the root
        // cause of "messages don't pass between phones".
        val socket: BluetoothSocket = try {
            device.createInsecureRfcommSocketToServiceRecord(BluetoothConstants.APP_UUID)
        } catch (e: IOException) {
            BlueWaveLogger.w(TAG, "createInsecureRfcommSocketToServiceRecord failed for $key: ${e.message}")
            return
        } catch (e: SecurityException) {
            BlueWaveLogger.w(TAG, "createInsecureRfcommSocketToServiceRecord denied by permissions for $key", e)
            return
        }

        try {
            withContext(Dispatchers.IO) { socket.connect() }
        } catch (e: IOException) {
            BlueWaveLogger.w(TAG, "connect() failed for $key: ${e.message}")
            try { socket.close() } catch (_: IOException) { /* best effort */ }
            return
        } catch (e: SecurityException) {
            BlueWaveLogger.w(TAG, "connect() denied by permissions for $key", e)
            try { socket.close() } catch (_: IOException) { /* best effort */ }
            return
        }

        try {
            attachSession(socket)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            BlueWaveLogger.w(TAG, "attachSession failed for outbound socket to $key", e)
            try { socket.close() } catch (_: IOException) { /* best effort */ }
        }
    }

    override suspend fun send(macAddress: String, payload: ByteArray): Boolean {
        val key = macAddress.uppercase()
        val session = sessions[key] ?: return false
        val ok = session.send(payload)
        if (!ok) {
            // Let onClosed evict the session and emit sessionDetached.
            session.cancel()
        }
        return ok
    }

    override fun disconnect(macAddress: String) {
        val key = macAddress.uppercase()
        val session = sessions[key] ?: return
        session.cancel()
    }

    /**
     * Returns `true` when a live session exists for [macAddress].
     * Useful for the UI to drive a "connected" indicator without
     * subscribing to a separate state flow — the cost is a single
     * concurrent map lookup.
     */
    override fun isConnected(macAddress: String): Boolean =
        sessions.containsKey(macAddress.uppercase())

    /**
     * Returns the most recently measured round-trip heartbeat latency
     * in milliseconds for [macAddress], or `null` when no session
     * exists or no measurement has been taken yet.
     */
    fun getPingMs(macAddress: String): Long? {
        val session = sessions[macAddress.uppercase()] ?: return null
        val ping = session.lastPingMs
        return if (ping > 0L) ping else null
    }

    private companion object {
        const val TAG = "BluetoothSessionManager"
    }
}

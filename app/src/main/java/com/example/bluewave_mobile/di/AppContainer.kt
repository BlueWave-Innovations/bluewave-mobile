package com.example.bluewave_mobile.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.bluewave_mobile.crypto.CryptoManager
import com.example.bluewave_mobile.crypto.KeyManager
import com.example.bluewave_mobile.crypto.LibSignalEngine
import com.example.bluewave_mobile.crypto.SignalEngine
import com.example.bluewave_mobile.data.AppDatabase
import com.example.bluewave_mobile.data.DatabaseProvider
import com.example.bluewave_mobile.data.MessageDao
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.MessageRepositoryImpl
import com.example.bluewave_mobile.network.ApkSender
import com.example.bluewave_mobile.network.BlueWaveSdpProber
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.network.BluetoothSessionManager
import com.example.bluewave_mobile.network.MessageTransport
import com.example.bluewave_mobile.preferences.UserPreferencesRepository
import com.example.bluewave_mobile.preferences.bluewavePreferencesDataStore

/**
 * Manual dependency injection container — the BlueWave equivalent of a
 * Hilt `SingletonComponent`.
 *
 * For a hackathon-scale project the runtime cost of a fully-fledged DI
 * framework (Hilt / Koin) is not worth the build-time hit, so we hand
 * out a single [AppContainer] from
 * [android.app.Application.onCreate] and feed it into ViewModels via
 * a small `ViewModelProvider.Factory` (Developer 2's responsibility).
 *
 * Every collaborator is exposed through a [Lazy] property so:
 *  * we never instantiate the [AppDatabase] (a heavy SQLite open call)
 *    on the main thread until the first repository access;
 *  * unit / instrumentation tests can substitute a fake by overriding a
 *    single property in a subclass;
 *  * the construction order is decided lazily by Kotlin, eliminating
 *    chicken-and-egg failures between, say, [KeyManager] and
 *    [CryptoManager].
 *
 * **No ViewModel may hold a hard reference to a concrete data class** —
 * everything goes through the abstractions exposed here.
 */
class AppContainer(applicationContext: Context) {

    private val appContext: Context = applicationContext.applicationContext

    /** Process-wide Room database singleton. */
    val database: AppDatabase by lazy {
        DatabaseProvider.getDatabase(appContext)
    }

    /** DAO handle for [com.example.bluewave_mobile.data.MessageEntity]. */
    val messageDao: MessageDao by lazy {
        database.messageDao()
    }

    /** Android Keystore-backed AES-256 key holder. */
    val keyManager: KeyManager by lazy {
        KeyManager()
    }

    /** AES-256-GCM encrypt / decrypt facade backed by [keyManager]. */
    val cryptoManager: CryptoManager by lazy {
        CryptoManager(keyManager)
    }

    /**
     * Single Source of Truth for message data exposed to the UI layer.
     * The interface type [MessageRepository] is intentionally returned
     * so ViewModels never depend on the concrete impl.
     *
     * Wires the freshly-constructed [BluetoothSessionManager] in as
     * the [MessageTransport] so `sendMessage` actually pushes bytes
     * over RFCOMM and incoming frames flow back into Room.
     */
    val messageRepository: MessageRepository by lazy {
        MessageRepositoryImpl(
            messageDao = messageDao,
            cryptoManager = cryptoManager,
            transport = bluetoothSessionManager,
            signalEngine = signalEngine,
        )
    }

    /**
     * Process-wide [SignalEngine] backed by libsignal's X3DH +
     * Double Ratchet primitives. The engine owns a fresh identity
     * key pair per cold launch (in-memory store — see
     * [LibSignalEngine] for the persistence trade-off).
     */
    val signalEngine: SignalEngine by lazy {
        LibSignalEngine.create()
    }

    /**
     * System BluetoothAdapter handle. Lazily resolved through
     * [BluetoothManager] (the post-API 31 way; the deprecated
     * `BluetoothAdapter.getDefaultAdapter()` is intentionally avoided).
     * Returns `null` on emulators / devices without a BT chipset, in
     * which case the network classes degrade gracefully.
     */
    val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    /** Reactive wrapper over [BluetoothAdapter.startDiscovery]. */
    val bluetoothDiscovery: BluetoothDiscovery by lazy {
        BluetoothDiscovery(appContext, bluetoothAdapter)
    }

    /**
     * Process-wide RFCOMM session manager. Owns the perpetual accept
     * loop and the per-peer [com.example.bluewave_mobile.network.BluetoothSession]
     * map. `BlueWaveApplication.onCreate` is responsible for calling
     * [BluetoothSessionManager.start] once permissions are available
     * and for forwarding [BluetoothSessionManager.incoming] into
     * [messageRepository].
     */
    val bluetoothSessionManager: BluetoothSessionManager by lazy {
        BluetoothSessionManager(bluetoothAdapter)
    }

    /**
     * Reactive SDP probe used by the device-list screen to decide
     * whether a discovered peer already runs BlueWave (lands in the
     * "Can start chat" section) or needs the install CTA (lands in
     * "No app yet"). The receiver is started by [BlueWaveApplication]
     * at process start-up.
     */
    val sdpProber: BlueWaveSdpProber by lazy {
        BlueWaveSdpProber(appContext, bluetoothAdapter)
    }

    /**
     * APK transfer helper backing the "Suggest install" action on
     * peers that don't yet run BlueWave — delegates to the system
     * Bluetooth share UI through `Intent.ACTION_SEND`.
     */
    val apkSender: ApkSender by lazy {
        ApkSender(appContext)
    }

    /**
     * DataStore-backed source of truth for user preferences —
     * theme mode, UI language, the local profile card, and the
     * Bluetooth-visibility timer. ViewModels for the Settings and
     * Profile tabs pull this in directly through the
     * [androidx.lifecycle.viewmodel.viewmodel.compose.viewModel]
     * factory.
     */
    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(appContext.bluewavePreferencesDataStore)
    }
}

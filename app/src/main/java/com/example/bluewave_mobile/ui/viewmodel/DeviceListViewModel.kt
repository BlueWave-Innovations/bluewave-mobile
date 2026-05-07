package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.data.BluetoothDeviceInfo
import com.example.bluewave_mobile.network.BluetoothDiscovery
import com.example.bluewave_mobile.ui.intent.DeviceListIntent
import com.example.bluewave_mobile.ui.state.DeviceListUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel that drives the device-list screen.
 *
 * The model is a plain MVI reducer:
 *  * UI sends a [DeviceListIntent] through [handleIntent].
 *  * The reducer mutates [_uiState] (and, for scans, kicks off the
 *    cold [BluetoothDiscovery.discoverDevices] flow on
 *    `viewModelScope`).
 *  * The composable observes the resulting [uiState] via
 *    `collectAsStateWithLifecycle`.
 *
 * **`stateIn` semantics.** A naive ViewModel would expose
 * `_uiState.asStateFlow()` directly, which keeps the discovery flow
 * collected even after the screen leaves composition (e.g. user
 * rotates the device, briefly backgrounded). Wrapping with
 * `stateIn(SharingStarted.WhileSubscribed(5000), Idle)` gives us:
 *
 *  * Configuration changes do **not** cancel the scan, so flipping
 *    the device 180° doesn't drop the partial device list — we keep
 *    the upstream live for 5 s of "no subscribers".
 *  * Putting the app in the background *does* eventually cancel the
 *    flow once the 5 s grace window elapses, releasing the radio
 *    chipset (a non-trivial battery saving on Android 16).
 *
 * Constructor takes a [BluetoothDiscovery] rather than the singleton
 * [BlueWaveApplication.container] so the unit tests in step 40 can
 * drop in a fake without touching the global container.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceListViewModel(
    private val bluetoothDiscovery: BluetoothDiscovery
) : ViewModel() {

    private val intents: MutableSharedFlow<DeviceListIntent> = MutableSharedFlow(extraBufferCapacity = 16)

    private val _uiState: MutableStateFlow<DeviceListUiState> = MutableStateFlow(DeviceListUiState.Idle)

    /**
     * Public, lifecycle-friendly snapshot of the screen state. Wrapped
     * with `stateIn` so the upstream collection survives short-lived
     * unsubscriptions (configuration changes) but is released after
     * 5 s of true inactivity.
     */
    val uiState: StateFlow<DeviceListUiState> = _uiState
        .asStateFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = DeviceListUiState.Idle
        )

    init {
        // The reducer runs for the lifetime of the ViewModel; viewModelScope
        // cancels it on ViewModel.onCleared, which is the canonical
        // structured-concurrency boundary for this layer.
        viewModelScope.launch {
            intents
                .flatMapLatest { intent -> reduce(intent) }
                .catch { throwable ->
                    emit(DeviceListUiState.Error(throwable.message ?: "Discovery failed"))
                }
                .collect { newState ->
                    _uiState.value = newState
                }
        }
    }

    /** Submit an intent for the reducer to process. */
    fun handleIntent(intent: DeviceListIntent) {
        intents.tryEmit(intent)
    }

    /**
     * Pure-function reducer: maps an [intent] to a [Flow] of
     * [DeviceListUiState] emissions. Returning a flow (instead of a
     * single state) lets [DeviceListIntent.StartScan] stream the
     * intermediate `Scanning(devices)` snapshots while discovery is
     * still running.
     */
    private fun reduce(intent: DeviceListIntent): Flow<DeviceListUiState> = when (intent) {
        DeviceListIntent.StartScan -> scanFlow()
        DeviceListIntent.StopScan -> {
            val current = _uiState.value
            val devices = if (current is DeviceListUiState.Scanning) current.devices else emptyList()
            flowOf(DeviceListUiState.Loaded(devices))
        }
        DeviceListIntent.PermissionsGranted -> flowOf(DeviceListUiState.Idle)
        is DeviceListIntent.DeviceSelected -> flowOf(_uiState.value) // pure navigation
    }

    /**
     * Cold flow that performs a single discovery cycle and emits
     * incremental [DeviceListUiState.Scanning] / final
     * [DeviceListUiState.Loaded] / fault [DeviceListUiState.BluetoothDisabled]
     * snapshots.
     */
    private fun scanFlow(): Flow<DeviceListUiState> = flow {
        val seen: MutableMap<String, BluetoothDeviceInfo> = LinkedHashMap()
        bluetoothDiscovery.bondedDevices().forEach { seen[it.macAddress] = it }
        emit(DeviceListUiState.Scanning(seen.values.toList()))
        try {
            bluetoothDiscovery.discoverDevices().collect { device ->
                seen[device.macAddress] = device
                emit(DeviceListUiState.Scanning(seen.values.toList()))
            }
            emit(DeviceListUiState.Loaded(seen.values.toList()))
        } catch (e: IllegalStateException) {
            emit(DeviceListUiState.BluetoothDisabled)
        }
    }

    companion object {
        /**
         * `ViewModelProvider.Factory` that pulls the
         * [BluetoothDiscovery] singleton out of the
         * [BlueWaveApplication.container]. Compose host:
         *
         * ```kotlin
         * val vm: DeviceListViewModel = viewModel(factory = DeviceListViewModel.Factory)
         * ```
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                DeviceListViewModel(app.container.bluetoothDiscovery)
            }
        }
    }
}

/**
 * Extracts the [BlueWaveApplication] from `CreationExtras`. Throws
 * with a precise message if the ViewModel is created outside of an
 * Activity / Application context (e.g. in a unit test that forgot to
 * supply `APPLICATION_KEY`).
 */
internal fun CreationExtras.application(): BlueWaveApplication =
    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
        "ViewModel created outside of an Activity; APPLICATION_KEY is null"
    } as BlueWaveApplication

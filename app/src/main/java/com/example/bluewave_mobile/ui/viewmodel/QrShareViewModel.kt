package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.network.QrCodeEncoder
import com.example.bluewave_mobile.network.QrContactPayload
import com.example.bluewave_mobile.preferences.UserPreferencesRepository
import com.example.bluewave_mobile.ui.state.QrShareUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel that drives the QR share / scan screen.
 *
 * On creation it stitches together the persisted [LocalProfile]
 * (display name, handle) and the local Bluetooth MAC supplied by
 * [localMacProvider] into a [QrContactPayload] and asks
 * [QrCodeEncoder] to render it as a bitmap. The bitmap encode runs on
 * [Dispatchers.Default] so the main thread stays free while the
 * matrix is computed.
 *
 * Scanning is implemented as a paste-to-decode fallback today —
 * [parsePastedUri] forwards a clipboard-pasted `bluewave://contact`
 * URI through [QrContactPayload.parse]. A camera-driven scanner
 * is tracked as future work; the fallback already covers the most
 * common share path (one user copies the link, the other pastes).
 */
class QrShareViewModel(
    private val preferences: UserPreferencesRepository,
    private val localMacProvider: () -> String,
) : ViewModel() {

    private val _uiState: MutableStateFlow<QrShareUiState> = MutableStateFlow(QrShareUiState.Loading)

    /** Snapshot of the QR-share screen state. */
    val uiState: StateFlow<QrShareUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { loadOnce() }
    }

    /**
     * Recompute the QR. Called from the screen when the user lands
     * back from editing the profile so the deep link refreshes.
     */
    fun refresh() {
        viewModelScope.launch { loadOnce() }
    }

    /**
     * Decode a pasted `bluewave://contact` URI into a structured
     * [QrContactPayload]. Returns `null` for malformed or
     * non-BlueWave URIs so the screen can surface a localised error
     * snackbar without throwing.
     */
    fun parsePastedUri(input: String): QrContactPayload? = QrContactPayload.parse(input)

    private suspend fun loadOnce() {
        _uiState.value = QrShareUiState.Loading
        val profile = preferences.localProfile.first()
        val mac = localMacProvider().uppercase()
        if (mac.isBlank()) {
            _uiState.value = QrShareUiState.NoMac
            return
        }
        val payload = QrContactPayload(
            macAddress = mac,
            displayName = profile.displayName,
            handle = profile.handle,
        )
        val bitmap = withContext(Dispatchers.Default) {
            QrCodeEncoder.encode(payload)
        }
        _uiState.value = if (bitmap == null) {
            QrShareUiState.NoMac
        } else {
            QrShareUiState.Ready(
                payload = payload,
                bitmap = bitmap,
                deepLink = payload.toUri(),
            )
        }
    }

    companion object {
        /**
         * `ViewModelProvider.Factory` that pulls dependencies out of
         * the [com.example.bluewave_mobile.di.AppContainer]. Compose
         * host:
         *
         * ```kotlin
         * val vm: QrShareViewModel = viewModel(factory = QrShareViewModel.Factory)
         * ```
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                QrShareViewModel(
                    preferences = app.container.userPreferencesRepository,
                    localMacProvider = {
                        app.container.bluetoothAdapter?.address?.uppercase().orEmpty()
                    },
                )
            }
        }
    }
}

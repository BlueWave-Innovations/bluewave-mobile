package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.preferences.AppLanguage
import com.example.bluewave_mobile.preferences.BluetoothVisibility
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.preferences.ThemeMode
import com.example.bluewave_mobile.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.example.bluewave_mobile.utils.BlueWaveLogger
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings tab.
 *
 * Surfaces every preference the user can tweak as a [StateFlow] so
 * Compose pickers re-render reactively, and exposes a tiny set of
 * setters that delegate to [UserPreferencesRepository].
 *
 * The Bluetooth-visibility setter is intentionally only a *persist*
 * step — actually surfacing the system
 * `ACTION_REQUEST_DISCOVERABLE` dialog needs an Activity context, so
 * the screen launches the intent itself and only round-trips through
 * the ViewModel to remember the user's pick for the next cold launch.
 */
class SettingsViewModel(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    /** Profile card shown at the top of Settings. */
    val profile: StateFlow<LocalProfile> = preferences.localProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LocalProfile.EMPTY,
    )

    /** Currently-selected theme. */
    val themeMode: StateFlow<ThemeMode> = preferences.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ThemeMode.SYSTEM,
    )

    /** Currently-selected UI language. */
    val appLanguage: StateFlow<AppLanguage> = preferences.appLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = AppLanguage.SYSTEM,
    )

    /** Currently-selected Bluetooth-visibility timer. */
    val bluetoothVisibility: StateFlow<BluetoothVisibility> = preferences.bluetoothVisibility.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = BluetoothVisibility.DEFAULT,
    )

    fun setThemeMode(value: ThemeMode) {
        BlueWaveLogger.i("SettingsViewModel", "setThemeMode: $value")
        viewModelScope.launch { preferences.setThemeMode(value) }
    }

    fun setAppLanguage(value: AppLanguage) {
        BlueWaveLogger.i("SettingsViewModel", "setAppLanguage: $value")
        viewModelScope.launch { preferences.setAppLanguage(value) }
    }

    fun setBluetoothVisibility(value: BluetoothVisibility) {
        BlueWaveLogger.i("SettingsViewModel", "setBluetoothVisibility: $value")
        viewModelScope.launch { preferences.setBluetoothVisibility(value) }
    }

    val discoverableBannerDismissed: StateFlow<Boolean> =
        preferences.isDiscoverableBannerDismissed.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    fun setDiscoverableBannerDismissed() {
        BlueWaveLogger.i("SettingsViewModel", "setDiscoverableBannerDismissed")
        viewModelScope.launch { preferences.setDiscoverableBannerDismissed() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                SettingsViewModel(app.container.userPreferencesRepository)
            }
        }
    }
}

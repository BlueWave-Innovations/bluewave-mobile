package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel that drives the **Profile** tab.
 *
 * Owns no business logic of its own — it is a thin reducer that
 * exposes the current [LocalProfile] as a [StateFlow] and forwards
 * field-level edits to [UserPreferencesRepository.setLocalProfile].
 * The handle is canonicalised through [LocalProfile.canonicalHandle]
 * so two users typing `alex_j` and `@alex_j` produce identical
 * persisted state.
 *
 * The push-on-edit side of the profile pipeline is wired in
 * `BlueWaveApplication`: a long-lived collector watches
 * [UserPreferencesRepository.localProfile] and calls
 * [com.example.bluewave_mobile.data.MessageRepository.onLocalProfileChanged]
 * whenever the persisted card changes — so the ViewModel itself does
 * not need to talk to the network layer.
 */
class ProfileViewModel(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    /** Lifecycle-aware view of the persisted local profile. */
    val profile: StateFlow<LocalProfile> = preferences.localProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LocalProfile.EMPTY,
    )

    /** Update the display name; trims trailing whitespace. */
    fun setDisplayName(value: String) {
        update { it.copy(displayName = value.trim()) }
    }

    /**
     * Update the @handle, normalising leading `@` so the persisted
     * value is always canonical (`@user` or empty).
     */
    fun setHandle(value: String) {
        update { it.copy(handle = LocalProfile.canonicalHandle(value)) }
    }

    /** Update the free-form bio. */
    fun setBio(value: String) {
        update { it.copy(bio = value) }
    }

    /** Update the avatar URI (typically a `content://` from Photo Picker). */
    fun setAvatarUri(value: String) {
        update { it.copy(avatarUri = value.trim()) }
    }

    private fun update(transform: (LocalProfile) -> LocalProfile) {
        viewModelScope.launch {
            preferences.setLocalProfile(transform(profile.value))
        }
    }

    companion object {
        /**
         * `ViewModelProvider.Factory` pulling the
         * [UserPreferencesRepository] out of the application
         * container. Used by the Profile tab via
         * `viewModel(factory = ProfileViewModel.Factory)`.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                ProfileViewModel(app.container.userPreferencesRepository)
            }
        }
    }
}

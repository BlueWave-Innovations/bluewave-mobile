package com.example.bluewave_mobile.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.data.MessageRepository
import com.example.bluewave_mobile.data.PeerProfileEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Lightweight ViewModel that surfaces the cached [PeerProfileEntity]
 * for a single remote peer.
 *
 * The profile is read-only — the peer owns the data and pushes
 * updates via `PROFILE_METADATA` frames.
 */
class PeerProfileViewModel(
    deviceMac: String,
    repository: MessageRepository,
) : ViewModel() {

    /** Reactive view of the peer's cached profile card. */
    val peerProfile: StateFlow<PeerProfileEntity?> = repository
        .observePeerProfile(deviceMac)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    companion object {
        const val ARG_DEVICE_MAC: String = "deviceMac"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = application()
                val handle: SavedStateHandle = createSavedStateHandle()
                val mac: String = checkNotNull(handle[ARG_DEVICE_MAC]) {
                    "PeerProfileViewModel requires SavedStateHandle[\"$ARG_DEVICE_MAC\"]"
                }
                PeerProfileViewModel(
                    deviceMac = mac,
                    repository = app.container.messageRepository,
                )
            }
        }
    }
}

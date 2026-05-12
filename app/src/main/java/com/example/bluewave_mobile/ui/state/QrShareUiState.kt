package com.example.bluewave_mobile.ui.state

import android.graphics.Bitmap
import com.example.bluewave_mobile.network.QrContactPayload

/**
 * State exposed by [com.example.bluewave_mobile.ui.viewmodel.QrShareViewModel].
 *
 * Three logical branches drive the QR share screen:
 *
 *  * [Loading] — the local profile / Bluetooth MAC are still being
 *    resolved (lasts a single coroutine tick in practice).
 *  * [NoMac] — the local Bluetooth adapter could not return a MAC
 *    address (emulator, BT off, or runtime permission missing). The
 *    screen renders the "set up your profile first" empty state.
 *  * [Ready] — the QR is rendered as a [Bitmap] and the deep link is
 *    cached for the copy / share buttons.
 */
sealed interface QrShareUiState {

    /** Initial / refreshing state — the screen renders a spinner. */
    data object Loading : QrShareUiState

    /**
     * The local Bluetooth MAC could not be resolved or the user has
     * not set a display name yet. The screen surfaces the configured
     * `qr_share_no_profile` empty-state message.
     */
    data object NoMac : QrShareUiState

    /**
     * Ready-to-render snapshot.
     *
     * @property payload Source of truth for the embedded `bluewave://`
     *                   URI; surfaced separately from [bitmap] so the
     *                   copy / share buttons don't need to round-trip
     *                   through the bitmap.
     * @property bitmap  Pre-rendered QR — non-null because we only
     *                   reach this branch after the encoder produced
     *                   a frame.
     * @property deepLink Cached `payload.toUri()` so the screen can
     *                   bind it directly to clipboard / share intent.
     */
    data class Ready(
        val payload: QrContactPayload,
        val bitmap: Bitmap,
        val deepLink: String,
    ) : QrShareUiState
}

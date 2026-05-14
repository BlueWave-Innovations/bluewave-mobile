package com.example.bluewave_mobile.preferences

/**
 * The user's own profile card as it lives on this device.
 *
 * Persisted in [UserPreferencesRepository] (DataStore Preferences),
 * exposed to peers as part of the
 * [com.example.bluewave_mobile.network.BlueWaveFrame.Type.PROFILE_METADATA]
 * envelope right after the libsignal X3DH handshake completes.
 *
 * @property displayName "Алекс Иванов" — what the chat list and
 *   peer's profile screen render in lieu of a MAC address.
 * @property handle Telegram-style "@alex_j" tag, treated as a
 *   light-weight, human-friendly identifier. Always normalised to
 *   start with a single leading `@` (or empty when not set).
 * @property bio Free-form one-liner ("Designing apps in BlueWave").
 *   May be empty.
 * @property avatarUri `content://` URI of the picked avatar
 *   image, or empty when the user has not set one. The URI is
 *   resolved on the local device only — it is **not** transmitted
 *   to peers (the peer-side avatar comes from a future binary
 *   field, not from a URI that wouldn't resolve cross-process).
 */
data class LocalProfile(
    val displayName: String,
    val handle: String,
    val bio: String,
    val avatarUri: String,
) {
    companion object {
        /** Empty profile used as the DataStore default. */
        val EMPTY: LocalProfile = LocalProfile(
            displayName = "",
            handle = "",
            bio = "",
            avatarUri = "",
        )

        /**
         * Normalise the user-typed handle into a canonical form:
         * trim whitespace, strip leading `@` characters, then
         * re-prefix with a single `@` when non-empty.
         */
        fun canonicalHandle(raw: String): String {
            val trimmed = raw.trim().trimStart('@')
            return if (trimmed.isEmpty()) "" else "@$trimmed"
        }
    }
}

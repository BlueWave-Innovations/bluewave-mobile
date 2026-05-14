package com.example.bluewave_mobile.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed source of truth for everything the user can
 * tweak from the **Settings** and **Profile** screens:
 *
 *  * theme mode (system / light / dark);
 *  * UI language (system / English / Russian);
 *  * the local profile card (display name, @handle, bio, avatar URI);
 *  * the Bluetooth-visibility timer.
 *
 * Each value is exposed as a [Flow] so Compose screens can observe
 * the latest snapshot without polling, and as a `setX` suspending
 * mutator that issues a single atomic [Preferences.edit] write.
 */
class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * Hot stream of the persisted [ThemeMode].
     *
     * Emits [ThemeMode.SYSTEM] on first ever read (DataStore returns
     * an empty preferences object); thereafter emits whatever was
     * last written via [setThemeMode].
     */
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { prefs -> ThemeMode.fromKey(prefs[KEY_THEME_MODE]) }

    /** Persist a new [ThemeMode]. */
    suspend fun setThemeMode(value: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = value.name }
    }

    /** Hot stream of the persisted [AppLanguage]. */
    val appLanguage: Flow<AppLanguage> =
        dataStore.data.map { prefs -> AppLanguage.fromKey(prefs[KEY_APP_LANGUAGE]) }

    /** Persist a new [AppLanguage]. */
    suspend fun setAppLanguage(value: AppLanguage) {
        dataStore.edit { it[KEY_APP_LANGUAGE] = value.name }
    }

    /** Hot stream of the persisted [LocalProfile]. */
    val localProfile: Flow<LocalProfile> =
        dataStore.data.map { prefs ->
            LocalProfile(
                displayName = prefs[KEY_PROFILE_DISPLAY_NAME].orEmpty(),
                handle = prefs[KEY_PROFILE_HANDLE].orEmpty(),
                bio = prefs[KEY_PROFILE_BIO].orEmpty(),
                avatarUri = prefs[KEY_PROFILE_AVATAR_URI].orEmpty(),
            )
        }

    /**
     * Persist a fresh [LocalProfile]. Empty fields are still written
     * so the user can clear a previously-set value (e.g. unset bio).
     * The [LocalProfile.handle] is canonicalised at the call site
     * before reaching this method.
     */
    suspend fun setLocalProfile(value: LocalProfile) {
        dataStore.edit { prefs ->
            prefs[KEY_PROFILE_DISPLAY_NAME] = value.displayName
            prefs[KEY_PROFILE_HANDLE] = value.handle
            prefs[KEY_PROFILE_BIO] = value.bio
            prefs[KEY_PROFILE_AVATAR_URI] = value.avatarUri
        }
    }

    /** Hot stream of the persisted [BluetoothVisibility]. */
    val bluetoothVisibility: Flow<BluetoothVisibility> =
        dataStore.data.map { prefs -> BluetoothVisibility.fromKey(prefs[KEY_BT_VISIBILITY]) }

    /** Persist a new [BluetoothVisibility]. */
    suspend fun setBluetoothVisibility(value: BluetoothVisibility) {
        dataStore.edit { it[KEY_BT_VISIBILITY] = value.name }
    }

    /** Whether the one-time BT visibility prompt has been shown. */
    val isBtVisibilityPromptShown: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_BT_VISIBILITY_PROMPT_SHOWN] ?: false }

    /** Mark the BT visibility prompt as shown. */
    suspend fun setBtVisibilityPromptShown() {
        dataStore.edit { it[KEY_BT_VISIBILITY_PROMPT_SHOWN] = true }
    }

    private companion object {
        // Keep keys explicit so a future migration or rename does
        // not silently zero out values.
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_PROFILE_DISPLAY_NAME = stringPreferencesKey("profile_display_name")
        val KEY_PROFILE_HANDLE = stringPreferencesKey("profile_handle")
        val KEY_PROFILE_BIO = stringPreferencesKey("profile_bio")
        val KEY_PROFILE_AVATAR_URI = stringPreferencesKey("profile_avatar_uri")
        val KEY_BT_VISIBILITY = stringPreferencesKey("bluetooth_visibility")
        val KEY_BT_VISIBILITY_PROMPT_SHOWN = booleanPreferencesKey("bt_visibility_prompt_shown")
    }
}

/**
 * Module-private DataStore handle attached to [Context].
 *
 * Exposed as `Context.bluewavePreferencesDataStore` so
 * [com.example.bluewave_mobile.di.AppContainer] can hand a
 * [UserPreferencesRepository] to ViewModels without dragging
 * the property delegate plumbing into the DI surface.
 */
internal val Context.bluewavePreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "bluewave_user_prefs")

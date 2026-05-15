package com.example.bluewave_mobile.preferences

/**
 * Tri-state theme selector backing the "Тема" picker on the
 * settings screen.
 *
 * The mode is persisted in [UserPreferencesRepository] and applied
 * by [com.example.bluewave_mobile.ui.theme.BlueWaveTheme] which
 * resolves [SYSTEM] against `isSystemInDarkTheme()`.
 */
enum class ThemeMode {
    /** Follow the platform `Configuration.UI_MODE_NIGHT_*` flag. */
    SYSTEM,

    /** Force the [com.example.bluewave_mobile.ui.theme.BlueWaveTheme] light scheme. */
    LIGHT,

    /** Force the [com.example.bluewave_mobile.ui.theme.BlueWaveTheme] dark scheme. */
    DARK,
    ;

    companion object {
        /** Safe parser used when reading a previously-persisted value. */
        fun fromKey(key: String?): ThemeMode = when (key) {
            LIGHT.name -> LIGHT
            DARK.name -> DARK
            else -> SYSTEM
        }
    }
}

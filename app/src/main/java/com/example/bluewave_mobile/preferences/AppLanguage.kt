package com.example.bluewave_mobile.preferences

import androidx.core.os.LocaleListCompat

/**
 * BlueWave's hand-picked language menu.
 *
 * We do **not** expose every system locale through the settings
 * screen — only the two we actually ship `values-*` resources for.
 * Anything else falls back to [SYSTEM], which lets the platform
 * pick from the user's preferred-locale list.
 *
 * Persisted as the enum [name] and re-applied at every cold launch
 * via `AppCompatDelegate.setApplicationLocales`.
 */
enum class AppLanguage(val tag: String?) {
    /** Follow the system locale list (no override). */
    SYSTEM(tag = null),

    /** Force the `values-en` resource bundle. */
    ENGLISH(tag = "en"),

    /** Force the `values-ru` resource bundle. */
    RUSSIAN(tag = "ru"),
    ;

    /** Locale list to feed `AppCompatDelegate.setApplicationLocales`. */
    fun toLocaleList(): LocaleListCompat =
        if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }

    companion object {
        /** Safe parser used when reading a previously-persisted value. */
        fun fromKey(key: String?): AppLanguage = when (key) {
            ENGLISH.name -> ENGLISH
            RUSSIAN.name -> RUSSIAN
            else -> SYSTEM
        }
    }
}

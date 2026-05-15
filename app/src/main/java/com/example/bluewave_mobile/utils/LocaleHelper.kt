package com.example.bluewave_mobile.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.example.bluewave_mobile.preferences.AppLanguage
import java.util.Locale

/**
 * Manual per-app locale helper for apps that use [androidx.activity.ComponentActivity]
 * instead of [androidx.appcompat.app.AppCompatActivity].
 *
 * `AppCompatDelegate.setApplicationLocales` does NOT work with a plain
 * `ComponentActivity` because it needs an active `AppCompatDelegate` instance
 * to obtain the application context for the system `LocaleManager`. Without it
 * the call silently no-ops (or crashes on some OEM skins).
 *
 * This helper applies the locale by wrapping the activity's [Context] in
 * [attachBaseContext] and restarting the activity when the user picks a
 * different language.
 */
object LocaleHelper {

    /**
     * Wraps [base] with a configuration whose locale is set to the language
     * described by [appLanguage].
     *
     * Call this from `Activity.attachBaseContext` before `super.attachBaseContext`.
     */
    fun wrapContext(base: Context, appLanguage: AppLanguage): Context {
        val locale = appLanguage.toLocale()
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Applies [locale] to the current process without recreating the activity.
     * Useful on cold-start when the locale is read from disk before the first
     * activity window is created.
     */
    fun setLocale(locale: Locale) {
        val config = Configuration()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
    }

    private fun AppLanguage.toLocale(): Locale = when (this) {
        AppLanguage.SYSTEM -> Locale.getDefault()
        AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
        AppLanguage.RUSSIAN -> Locale.forLanguageTag("ru")
    }
}

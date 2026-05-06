package com.example.bluewave_mobile

import android.app.Application
import com.example.bluewave_mobile.di.AppContainer

/**
 * Application subclass that owns the process-wide [AppContainer].
 *
 * The container is instantiated exactly once per process in
 * [onCreate] and exposed as a public property so [MainActivity] /
 * ViewModelProviders can pull singletons without going through a
 * static `getInstance()` anti-pattern.
 *
 * Registered through `<application android:name=".BlueWaveApplication" />`
 * in `AndroidManifest.xml`.
 */
class BlueWaveApplication : Application() {

    /**
     * Process-wide DI container. Initialised on [onCreate] before any
     * Activity callback fires, so accessing it from `MainActivity` is
     * always safe.
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

package com.example.bluewave_mobile.data

import android.content.Context
import com.example.bluewave_mobile.BlueWaveApplication
import com.example.bluewave_mobile.di.AppContainer

/**
 * Type-safe accessor for the process-wide [MessageRepository] living in
 * [AppContainer].
 *
 * Up to this point Developer 2's UI scaffolding may have temporarily
 * referenced a hand-rolled `MockMessageRepository` for screen previews
 * while the data layer was being built. With step 49 the **real**
 * [MessageRepositoryImpl] (composed of Room + AES-256-GCM + Android
 * Keystore) becomes the single repository instance for the whole
 * application — no mock or fake implementation is ever instantiated at
 * runtime.
 *
 * Usage from any [androidx.lifecycle.ViewModel] factory or composable:
 *
 * ```kotlin
 * val repository = LocalContext.current.messageRepository
 * ```
 *
 * The extension is a one-liner that reaches through
 * [Context.applicationContext] -> [BlueWaveApplication] ->
 * [AppContainer.messageRepository], so it is safe to call from any
 * Activity, Fragment, ViewModel or Composable scope.
 *
 * Returning the [MessageRepository] interface (not the concrete
 * `MessageRepositoryImpl`) preserves the abstraction boundary: tests can
 * still replace the property with a mockk-based double via
 * `BlueWaveApplication.container = AppContainer(...)` without anyone in
 * the UI layer noticing.
 */
val Context.messageRepository: MessageRepository
    get() {
        val app = applicationContext as? BlueWaveApplication
            ?: error(
                "Context.applicationContext is not a BlueWaveApplication — " +
                    "is android:name=\".BlueWaveApplication\" set on <application> in AndroidManifest?"
            )
        return app.container.messageRepository
    }

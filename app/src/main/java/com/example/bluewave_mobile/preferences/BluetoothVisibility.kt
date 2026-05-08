package com.example.bluewave_mobile.preferences

/**
 * State of the "Видимость по Bluetooth" toggle on the settings
 * screen.
 *
 * The user picks a duration; we then use
 * `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE` to surface the
 * system dialog asking the platform to make the device
 * discoverable for that long. The same value is also used to
 * gate [com.example.bluewave_mobile.network.BluetoothSessionManager.start]
 * — when the user picks [OFF] we tear the accept-loop down so we
 * stop announcing the BlueWave RFCOMM service.
 */
enum class BluetoothVisibility(val durationSeconds: Int) {
    OFF(durationSeconds = 0),

    /** ~5 minutes, ideal for a quick "send my contact" handoff. */
    MIN_5(durationSeconds = 5 * 60),

    /** ~30 minutes, the default for a meeting or class session. */
    MIN_30(durationSeconds = 30 * 60),

    /** ~2 hours — the cap below the platform's hard 1-hour cap. */
    MIN_120(durationSeconds = 120 * 60),
    ;

    companion object {
        /** Default value when no preference has been written yet. */
        val DEFAULT: BluetoothVisibility = OFF

        /** Safe parser used when reading a previously-persisted value. */
        fun fromKey(key: String?): BluetoothVisibility = when (key) {
            MIN_5.name -> MIN_5
            MIN_30.name -> MIN_30
            MIN_120.name -> MIN_120
            OFF.name -> OFF
            else -> DEFAULT
        }
    }
}

package com.example.bluewave_mobile.preferences

/**
 * State of the "Видимость по Bluetooth" toggle on the settings
 * screen.
 *
 * The user picks a duration; we then use
 * `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE` to surface the
 * system dialog asking the platform to make the device
 * discoverable for that long, i.e. visible to **unpaired** peers
 * doing a fresh inquiry scan.
 *
 * The RFCOMM accept-loop in
 * [com.example.bluewave_mobile.network.BluetoothSessionManager] is
 * **not** gated by this setting: an already-paired peer can connect
 * to us over RFCOMM regardless of the system discoverable flag, and
 * keeping the server socket open is also how Android registers our
 * service UUID with the SDP database. Killing it just because the
 * user is not currently "discoverable" would silently break both
 * inbound messages and the SDP probe on the other peer (the remote
 * device would believe BlueWave is no longer installed).
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

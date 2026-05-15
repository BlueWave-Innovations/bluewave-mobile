package com.example.bluewave_mobile.data

/**
 * Domain model representing a discovered or paired Bluetooth device.
 *
 * This is NOT a Room entity — it's a lightweight data class used by the
 * network layer to pass device information to the UI through repositories.
 *
 * @property name Human-readable device name. Falls back to MAC address if unavailable.
 * @property macAddress Hardware MAC address of the Bluetooth device.
 * @property isPaired Whether this device is currently paired/bonded with the local adapter.
 * @property rssi Received Signal Strength Indicator in dBm, captured during
 *   discovery via `EXTRA_RSSI`. `null` for bonded devices that were not
 *   discovered in the current scan cycle.
 */
data class BluetoothDeviceInfo(
    val name: String,
    val macAddress: String,
    val isPaired: Boolean = false,
    val rssi: Short? = null,
)

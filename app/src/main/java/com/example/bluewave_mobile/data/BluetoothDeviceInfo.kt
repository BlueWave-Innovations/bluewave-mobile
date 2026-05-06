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
 */
data class BluetoothDeviceInfo(
    val name: String,
    val macAddress: String,
    val isPaired: Boolean = false
)

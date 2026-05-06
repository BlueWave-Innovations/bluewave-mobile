package com.example.bluewave_mobile.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
object DeviceListRoute

@Serializable
data class ChatRoute(val deviceMac: String)

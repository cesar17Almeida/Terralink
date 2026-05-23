package com.astralink.terralink.model

enum class ConnectionStatus { CONNECTED, DISCONNECTED }

data class Device(
    val name: String,
    val firmwareVersion: String,
    val model: String,
    val macAddress: String,
    val status: ConnectionStatus,
    val uptimeMinutes: Int,
    val lastSyncMinutesAgo: Int,
)

val mockDevice = Device(
    name = "Estación A1",
    firmwareVersion = "savia v0.1.0",
    model = "Raspberry Pi Zero 2 W",
    macAddress = "B8:27:EB:12:34:56",
    status = ConnectionStatus.CONNECTED,
    uptimeMinutes = 184,
    lastSyncMinutesAgo = 3,
)

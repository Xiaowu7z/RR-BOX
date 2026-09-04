package com.rr.client.vpn

data class NetworkContinuityState(
    val monitoring: Boolean = false,
    val transport: String = "--",
    val interfaceName: String = "--",
    val validated: Boolean = false,
    val switchCount: Long = 0L,
    val recoveryCount: Long = 0L,
    val lastSwitchAtMillis: Long = 0L,
    val lastHealthCheckAtMillis: Long = 0L,
    val healthy: Boolean = true,
    val lastEvent: String = "等待物理网络事件"
)

package com.rr.client.vpn

/**
 * Last completed VPN data-plane rebuild measurement.
 *
 * This is observability-only state used by Network Lab. It does not participate in routing,
 * configuration or lifecycle decisions.
 */
data class EngineRestartMeasurement(
    val serial: Long = 0L,
    val durationMillis: Long = 0L,
    val success: Boolean = false,
    val engine: String = ""
)

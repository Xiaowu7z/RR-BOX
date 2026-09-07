package com.rr.client.vpn

/** A queued settings update may only rebuild the same still-wanted VPN session. */
object RoutingUpdatePolicy {
    fun mayApply(
        desiredRunning: Boolean,
        expectedNodeId: String?,
        activeNodeId: String?,
        expectedGeneration: Long,
        activeGeneration: Long
    ): Boolean = desiredRunning && !expectedNodeId.isNullOrBlank() &&
        expectedNodeId == activeNodeId && expectedGeneration >= 0L &&
        expectedGeneration == activeGeneration
}

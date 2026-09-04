package com.rr.client.core

/** Keeps the live runtime node protected while allowing unrelated local-node cleanup. */
object LocalNodeDeletionPolicy {
    fun canDelete(nodeId: String, activeRuntimeNodeId: String?, vpnBusy: Boolean): Boolean {
        if (!vpnBusy) return true
        val active = activeRuntimeNodeId?.takeIf(String::isNotBlank) ?: return false
        return nodeId != active
    }
}

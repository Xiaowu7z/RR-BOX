package com.rr.client.core

/** Keeps the live runtime node protected while allowing unrelated local-node cleanup. */
object LocalNodeDeletionPolicy {
    fun canDelete(nodeId: String, activeRuntimeNodeId: String?, vpnBusy: Boolean): Boolean {
        return canDelete(nodeId, setOfNotNull(activeRuntimeNodeId?.takeIf(String::isNotBlank)), vpnBusy)
    }

    fun canDelete(nodeId: String, activeRuntimeNodeIds: Set<String>, vpnBusy: Boolean): Boolean {
        if (!vpnBusy) return true
        if (activeRuntimeNodeIds.isEmpty() || activeRuntimeNodeIds.any(String::isBlank)) return false
        return nodeId !in activeRuntimeNodeIds
    }
}

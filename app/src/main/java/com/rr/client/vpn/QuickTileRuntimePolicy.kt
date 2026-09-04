package com.rr.client.vpn

/** Pure cache-validity rules for the Quick Settings fast path. */
object QuickTileRuntimePolicy {
    fun matches(
        state: VpnRuntimeState,
        selectedNodeId: String?,
        smartRouting: Boolean,
        fastForwarding: Boolean,
        perAppMode: String,
        selectedPackages: Set<String>
    ): Boolean {
        if (state.configJson.isBlank()) return false
        if (selectedNodeId.isNullOrBlank() || state.nodeId != selectedNodeId) return false
        if (state.perAppMode != perAppMode) return false
        if (state.selectedPackages != selectedPackages) return false
        if (state.smartRouting != null && state.smartRouting != smartRouting) return false
        if (state.fastForwarding != null && state.fastForwarding != fastForwarding) return false
        return true
    }
}

package com.rr.client.vpn

/** Pure cache-validity rules for the Quick Settings fast path. */
object QuickTileRuntimePolicy {
    fun matches(
        state: VpnRuntimeState,
        selectedNodeId: String?,
        smartRouting: Boolean,
        fastForwarding: Boolean,
        perAppMode: String,
        selectedPackages: Set<String>,
        expectedConfigJson: String
    ): Boolean {
        if (state.configJson.isBlank() || state.configJson != expectedConfigJson) return false
        if (selectedNodeId.isNullOrBlank() || state.nodeId != selectedNodeId) return false
        if (state.perAppMode != perAppMode) return false
        if (state.selectedPackages != selectedPackages) return false
        if (state.smartRouting != smartRouting) return false
        if (state.fastForwarding != fastForwarding) return false
        return true
    }
}

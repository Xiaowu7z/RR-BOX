package com.rr.client.vpn

import com.rr.client.routing.AppNodeBinding
import com.rr.client.storage.AppNodeBindingsCodec

/** Pure cache-validity rules for the Quick Settings fast path. */
object QuickTileRuntimePolicy {
    fun resolveMainNodeId(availableIds: List<String>, selectedNodeId: String?, forcedMainNodeId: String?): String {
        if (forcedMainNodeId != null) {
            check(forcedMainNodeId.isNotBlank() && forcedMainNodeId in availableIds) {
                "原主节点已删除或不可用，请在 RRBOX 中重新选择主节点"
            }
            return forcedMainNodeId
        }
        require(availableIds.isNotEmpty()) { "还没有可用节点，请先在 RRBOX 中添加节点或订阅" }
        return selectedNodeId?.takeIf { it in availableIds } ?: availableIds.first()
    }

    fun matches(
        state: VpnRuntimeState,
        selectedNodeId: String?,
        smartRouting: Boolean,
        fastForwarding: Boolean,
        perAppMode: String,
        selectedPackages: Set<String>,
        expectedConfigJson: String,
        appNodeBindings: List<AppNodeBinding> = emptyList()
    ): Boolean {
        if (state.configJson.isBlank() || state.configJson != expectedConfigJson) return false
        if (selectedNodeId.isNullOrBlank() || state.nodeId != selectedNodeId) return false
        if (state.perAppMode != perAppMode) return false
        if (state.selectedPackages != selectedPackages) return false
        if (state.smartRouting != smartRouting) return false
        if (state.fastForwarding != fastForwarding) return false
        val savedBindings = state.appNodeBindings ?: return false
        if (AppNodeBindingsCodec.validate(savedBindings) != AppNodeBindingsCodec.validate(appNodeBindings)) return false
        return true
    }
}

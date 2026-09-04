package com.rr.client.vpn

import com.rr.client.routing.PerAppPolicyResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTileRuntimePolicyTest {
    private val base = VpnRuntimeState(
        configJson = "{\"outbounds\":[]}",
        nodeTag = "JP",
        nodeId = "node-1",
        perAppMode = PerAppPolicyResolver.MODE_ALL,
        selectedPackages = emptySet(),
        smartRouting = true,
        fastForwarding = false
    )

    @Test
    fun matchingRuntimeUsesFastPath() {
        assertTrue(
            QuickTileRuntimePolicy.matches(
                state = base,
                selectedNodeId = "node-1",
                smartRouting = true,
                fastForwarding = false,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet()
            )
        )
    }

    @Test
    fun nodeOrRoutingChangeInvalidatesCache() {
        assertFalse(
            QuickTileRuntimePolicy.matches(
                state = base,
                selectedNodeId = "node-2",
                smartRouting = true,
                fastForwarding = false,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet()
            )
        )
        assertFalse(
            QuickTileRuntimePolicy.matches(
                state = base,
                selectedNodeId = "node-1",
                smartRouting = false,
                fastForwarding = false,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet()
            )
        )
    }

    @Test
    fun legacyCacheWithoutNewFlagsIsAcceptedOnce() {
        val legacy = base.copy(smartRouting = null, fastForwarding = null)
        assertTrue(
            QuickTileRuntimePolicy.matches(
                state = legacy,
                selectedNodeId = "node-1",
                smartRouting = false,
                fastForwarding = true,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet()
            )
        )
    }
}

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
                selectedPackages = emptySet(),
                expectedConfigJson = base.configJson
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
                selectedPackages = emptySet(),
                expectedConfigJson = base.configJson
            )
        )
        assertFalse(
            QuickTileRuntimePolicy.matches(
                state = base,
                selectedNodeId = "node-1",
                smartRouting = false,
                fastForwarding = false,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet(),
                expectedConfigJson = base.configJson
            )
        )
    }

    @Test
    fun legacyCacheWithoutNewFlagsIsRebuilt() {
        val legacy = base.copy(smartRouting = null, fastForwarding = null)
        assertFalse(
            QuickTileRuntimePolicy.matches(
                state = legacy,
                selectedNodeId = "node-1",
                smartRouting = false,
                fastForwarding = true,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                selectedPackages = emptySet(),
                expectedConfigJson = base.configJson
            )
        )
    }
    @Test
    fun changedNodeCredentialsInvalidatesEvenWithSameNodeId() {
        assertFalse(QuickTileRuntimePolicy.matches(base, "node-1", true, false,
            PerAppPolicyResolver.MODE_ALL, emptySet(), "{\"outbounds\":[{\"password\":\"changed\"}]}"))
    }

    @Test
    fun selectedOnlyWithoutSmartRoutingReusesOnlyTheSameSelection() {
        val selected = setOf("com.openai.chatgpt", "com.google.android.gms")
        val saved = base.copy(
            perAppMode = PerAppPolicyResolver.MODE_ALLOW_LIST,
            selectedPackages = selected,
            smartRouting = false
        )
        fun matches(packages: Set<String> = selected, smart: Boolean = false,
                    mode: String = PerAppPolicyResolver.MODE_ALLOW_LIST) =
            QuickTileRuntimePolicy.matches(saved, "node-1", smart, false, mode, packages, saved.configJson)

        assertTrue(matches())
        assertFalse(matches(packages = selected + "org.telegram.messenger"))
        assertFalse(matches(packages = selected - "com.google.android.gms"))
        assertFalse(matches(packages = emptySet()))
        assertFalse(matches(smart = true))
        assertFalse(matches(mode = PerAppPolicyResolver.MODE_ALL))
    }

}

package com.rr.client.vpn

import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.AppNodeBinding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class QuickTileRuntimePolicyTest {
    @Test fun internalRecoveryKeepsTheLiveMainNodeDespiteAnUnappliedUiSelection() {
        assertEquals("la-main", QuickTileRuntimePolicy.resolveMainNodeId(
            listOf("hk", "la-main", "jp"), "jp", "la-main"
        ))
        assertEquals("jp", QuickTileRuntimePolicy.resolveMainNodeId(
            listOf("hk", "la-main", "jp"), "jp", null
        ))
    }

    @Test fun missingRecoveryMainNodeNeverSwitchesToAnArbitraryOtherExit() {
        try {
            QuickTileRuntimePolicy.resolveMainNodeId(listOf("hk", "jp"), "jp", "la-main")
            fail("An internal recovery must not choose another main exit")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("原主节点"))
        }
    }

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

    @Test fun everyBindingEditInvalidatesCacheEvenWhenItDoesNotAffectTheCurrentConfig() {
        val rule = AppNodeBinding("org.telegram.messenger", "hk", enabled = false)
        val saved = base.copy(appNodeBindings = listOf(rule))
        fun matches(bindings: List<AppNodeBinding>) = QuickTileRuntimePolicy.matches(
            saved, "node-1", true, false, PerAppPolicyResolver.MODE_ALL,
            emptySet(), saved.configJson, bindings
        )
        assertTrue(matches(listOf(rule)))
        assertFalse(matches(emptyList()))
        assertFalse(matches(listOf(rule.copy(nodeId = "jp"))))
        assertFalse(matches(listOf(rule.copy(enabled = true))))
    }

    @Test fun legacyCacheWithoutBindingsIsRebuiltAndRuleOrderingIsCanonical() {
        val rules = listOf(AppNodeBinding("org.telegram.messenger", "hk"), AppNodeBinding("com.openai.chatgpt", "la"))
        fun matches(state: VpnRuntimeState) = QuickTileRuntimePolicy.matches(
            state, "node-1", true, false, PerAppPolicyResolver.MODE_ALL,
            emptySet(), state.configJson, rules.reversed()
        )
        assertFalse(matches(base.copy(appNodeBindings = null)))
        assertTrue(matches(base.copy(appNodeBindings = rules)))
    }

    @Test fun changedAuxiliaryCredentialsInvalidateCacheEvenWhenMainAndBindingIdsRemainTheSame() {
        val rules = listOf(AppNodeBinding("org.telegram.messenger", "hk"))
        val saved = base.copy(appNodeBindings = rules)
        assertFalse(QuickTileRuntimePolicy.matches(saved, "node-1", true, false,
            PerAppPolicyResolver.MODE_ALL, emptySet(), "{\"outbounds\":[{\"server\":\"new-hk.example\"}]}", rules))
    }

}

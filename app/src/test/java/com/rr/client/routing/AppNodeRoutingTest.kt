package com.rr.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNodeRoutingTest {
    private val telegram = AppNodeBinding("org.telegram.messenger", "hk")
    private val chrome = AppNodeBinding("com.android.chrome", "la")

    @Test
    fun bindingsFollowCaptureScopeWithoutExpandingIt() {
        val bindings = listOf(telegram, chrome, AppNodeBinding("com.rr.client", "hk"))
        assertEquals(listOf(chrome, telegram), AppNodeRouting.activeBindings(bindings, "ALL", emptySet()))
        assertEquals(listOf(telegram), AppNodeRouting.activeBindings(bindings, "ALLOW_LIST", setOf(telegram.packageName)))
        assertEquals(listOf(chrome), AppNodeRouting.activeBindings(bindings, "DISALLOW_LIST", setOf(telegram.packageName)))
        assertTrue(AppNodeRouting.activeBindings(bindings, "ALLOW_LIST", emptySet()).isEmpty())
    }

    @Test
    fun disabledAndMalformedPackagesAreIgnoredButMissingNodeRemainsFailClosed() {
        val broken = telegram.copy(nodeId = "")
        val bindings = listOf(broken, chrome.copy(enabled = false), AppNodeBinding("../bad", "hk"))
        assertEquals(listOf(broken), AppNodeRouting.activeBindings(bindings, "ALL", emptySet()))
    }

    @Test
    fun identicalDuplicatesAreStableAndWhitespacePackagesAreNormalized() {
        assertEquals(listOf(telegram), AppNodeRouting.activeBindings(
            listOf(telegram.copy(packageName = " ${telegram.packageName} "), telegram),
            "ALLOW_LIST", setOf(" ${telegram.packageName} ")
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun conflictingActiveNodesForOnePackageAreRejected() {
        AppNodeRouting.activeBindings(listOf(telegram, telegram.copy(nodeId = "jp")), "ALL", emptySet())
    }

    @Test
    fun inactiveConflictsDoNotBreakMainCapture() {
        assertTrue(AppNodeRouting.activeBindings(
            listOf(telegram, telegram.copy(nodeId = "jp")), "DISALLOW_LIST", setOf(telegram.packageName)
        ).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCaptureModeIsRejected() {
        AppNodeRouting.activeBindings(emptyList(), "invalid", emptySet())
    }

    @Test
    fun tagsRoundTripExactUnicodeIdentitiesWithoutCollisionsOrPadding() {
        val ids = listOf("hk", "HK", "香港/Argo+一", "hk:1", "hk_1", "a b", "proxy")
        val tags = ids.map(AppNodeRouting::nodeTag)
        assertEquals(ids.size, tags.toSet().size)
        tags.zip(ids).forEach { (tag, id) ->
            assertTrue(tag.startsWith("rr-app-node-"))
            assertFalse(tag.contains('='))
            assertEquals(id, AppNodeRouting.nodeIdFromTag(tag))
        }
    }

    @Test
    fun malformedAndOtherTagNamespacesNeverBecomeNodeIdentities() {
        listOf("proxy", "direct", "rr-app-node-", "rr-app-node-aA==", "rr-app-node-!",
            "rr-app-node-_w", "rr-app-node-aB", "dns-${AppNodeRouting.nodeTag("hk")}").forEach {
            assertNull(it, AppNodeRouting.nodeIdFromTag(it))
        }
    }

    @Test
    fun requiredNodesComeFromLiveOutboundsAndNeverDnsOrStaleRuleReferences() {
        val hkTag = AppNodeRouting.nodeTag("hk")
        val removedTag = AppNodeRouting.nodeTag("removed")
        val config = """{"outbounds":[{"tag":"proxy"},{"tag":"$hkTag"},{"tag":"$hkTag"},{"tag":"direct"}],"route":{"rules":[{"outbound":"$removedTag"}]},"dns":{"servers":[{"tag":"dns-$removedTag"}]}}"""
        assertEquals(setOf("la", "hk"), AppNodeRouting.requiredNodeIds(config, "la"))
        assertEquals(setOf("hk"), AppNodeRouting.requiredNodeIds(config, ""))
    }

    @Test
    fun adapterOnlyRecognizesTheExactAutomaticUnknownOwnerGuard() {
        val guard = AppNodeRouting.unknownOwnerGuard()
        assertTrue(AppNodeRouting.isUnknownOwnerGuard(guard))
        guard.getAsJsonArray("rules")[1].asJsonObject.addProperty("invert", false)
        assertFalse(AppNodeRouting.isUnknownOwnerGuard(guard))
        assertTrue(AppNodeRouting.isUnknownOwnerGuard(AppNodeRouting.unknownOwnerGuard()))
        val otherAction = AppNodeRouting.unknownOwnerGuard().apply { addProperty("action", "route") }
        assertFalse(AppNodeRouting.isUnknownOwnerGuard(otherAction))
    }

    @Test
    fun explicitMainAndUnboundSharedUidSiblingUseTheSameOutlet() {
        AppNodeRouting.validateSharedUidTargets(
            listOf(telegram.copy(nodeId = "la")), mapOf(10001 to setOf(telegram.packageName, chrome.packageName)), "la"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun auxiliaryOutletCannotTakeAnUnboundSharedUidSiblingAwayFromMain() {
        AppNodeRouting.validateSharedUidTargets(
            listOf(telegram), mapOf(10001 to setOf(telegram.packageName, chrome.packageName)), "la"
        )
    }

    @Test
    fun everySharedUidSiblingMayUseTheSameExplicitAuxiliaryOutlet() {
        AppNodeRouting.validateSharedUidTargets(
            listOf(telegram, chrome.copy(nodeId = "hk")),
            mapOf(10001 to setOf(telegram.packageName, chrome.packageName)), "la"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun sharedUidCannotChooseTwoDifferentExplicitAuxiliaryOutlets() {
        AppNodeRouting.validateSharedUidTargets(
            listOf(telegram, chrome.copy(nodeId = "jp")),
            mapOf(10001 to setOf(telegram.packageName, chrome.packageName)), "la"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingOutboundsCannotSilentlyReduceDeletionProtectionToMain() {
        AppNodeRouting.requiredNodeIds("{}", "la")
    }
}

package com.rr.client.routing

import com.rr.client.storage.AppNodeBindingsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNodeBindingEditPlannerTest {
    private val first = "org.example.video"
    private val sibling = "org.example.video.helper"
    private val unrelated = AppNodeBinding("org.example.chat", "chat-node")
    private val bypassed = "org.example.local"
    private val group = AppNodeUidGroup(12001, setOf(first, sibling))
    private val modes = listOf("ALL", "ALLOW_LIST", "DISALLOW_LIST")
    private val proxyPackages = setOf(first, unrelated.packageName)
    private val bypassPackages = setOf(sibling, bypassed)
    private val assignedScopes = mapOf(
        "ALL" to (proxyPackages to bypassPackages),
        "ALLOW_LIST" to (setOf(first, sibling, unrelated.packageName) to bypassPackages),
        "DISALLOW_LIST" to (proxyPackages to setOf(bypassed))
    )

    private fun edit(
        operation: AppNodeEditOperation = AppNodeEditOperation.ASSIGN,
        nodeId: String? = "extra-node",
        enabled: Boolean? = null,
        confirmed: AppNodeUidGroup = group
    ) = AppNodeBindingEdit(first, operation, nodeId, enabled, confirmed)

    private fun plan(
        edit: AppNodeBindingEdit = edit(),
        current: AppNodeUidGroup = group,
        bindings: List<AppNodeBinding> = listOf(unrelated),
        mode: String = "ALLOW_LIST",
        proxy: Set<String> = proxyPackages,
        bypass: Set<String> = bypassPackages
    ) = AppNodeBindingEditPlanner.plan(edit, current, bindings, mode, proxy, bypass)

    private fun groupBindings(plan: AppNodeBindingEditPlan) =
        plan.bindings.filter { it.packageName in group.packages }.toSet()

    private fun expectedGroup(nodeId: String, enabled: Boolean = true) =
        group.packages.map { AppNodeBinding(it, nodeId, enabled) }.toSet()

    private fun activeBindings(plan: AppNodeBindingEditPlan, mode: String) =
        AppNodeRouting.activeBindings(plan.bindings, mode,
            if (mode == "DISALLOW_LIST") plan.bypassPackages else plan.proxyPackages)

    @Test
    fun ordinaryApplicationUidsAndOtherUsersCanShareAnAssignedOutlet() {
        listOf(10000, 10331, 11331, 19999, 110331).forEach { uid ->
            assertNull("Application UID $uid must be assignable",
                AppNodeUidGroupPolicy.blockedReason(uid, group.packages, 12045, "com.rr.client"))
        }
    }

    @Test
    fun coreAndIsolatedIdentitiesAreProtectedAcrossUsers() {
        listOf(-1, 0, 1000, 9999, 20000, 99000, 101000, 199000).forEach { uid ->
            assertNotNull("Core or isolated UID $uid must be protected",
                AppNodeUidGroupPolicy.blockedReason(uid, group.packages, 12045, "com.rr.client"))
        }
    }

    @Test
    fun ownUidAndAnyGroupContainingTheVpnPackageAreProtected() {
        assertNotNull(AppNodeUidGroupPolicy.blockedReason(12045, group.packages, 12045, "com.rr.client"))
        assertNotNull(AppNodeUidGroupPolicy.blockedReason(10331,
            group.packages + "com.rr.client", 12045, "com.rr.client"))
    }

    @Test
    fun systemInstalledApplicationsWithOrdinaryUidsRemainAssignable() {
        assertNull(AppNodeUidGroupPolicy.blockedReason(10331,
            setOf("com.android.example", "com.android.example.helper"), 12045, "com.rr.client"))
    }

    @Test
    fun assigningSharedUidNormalizesOnlyTheCurrentModeAndKeepsOtherUidsUnchanged() {
        modes.forEach { mode ->
            val result = plan(mode = mode)
            assertEquals(mode, expectedGroup("extra-node"), groupBindings(result))
            assertEquals(mode, assignedScopes.getValue(mode).first, result.proxyPackages)
            assertEquals(mode, assignedScopes.getValue(mode).second, result.bypassPackages)
            assertEquals(mode, listOf(unrelated), result.bindings.filter { it.packageName !in group.packages })
            assertEquals(mode, expectedGroup("extra-node"),
                activeBindings(result, mode).filter { it.packageName in group.packages }.toSet())
            AppNodeRouting.validateSharedUidTargets(activeBindings(result, mode),
                mapOf(group.uid!! to group.packages), "main-node")
        }
    }

    @Test
    fun switchingToAnIndependentConflictingScopeIsRejectedWithoutOverwritingThatScope() {
        listOf("ALL" to "ALLOW_LIST", "ALL" to "DISALLOW_LIST",
            "ALLOW_LIST" to "DISALLOW_LIST", "DISALLOW_LIST" to "ALLOW_LIST").forEach { (initialMode, nextMode) ->
            val result = plan(mode = initialMode)
            if (nextMode == "ALLOW_LIST") {
                assertEquals(proxyPackages, result.proxyPackages)
            } else {
                assertEquals(bypassPackages, result.bypassPackages)
            }
            val active = activeBindings(result, nextMode)
            assertEquals("$initialMode -> $nextMode", setOf(AppNodeBinding(first, "extra-node")),
                active.filter { it.packageName in group.packages }.toSet())
            assertThrows(IllegalArgumentException::class.java) {
                AppNodeRouting.validateSharedUidTargets(active, mapOf(group.uid!! to group.packages), "main-node")
            }
            // ALL ignores both independent lists and can use the complete explicit group.
            val all = activeBindings(result, "ALL")
            assertEquals(expectedGroup("extra-node"), all.filter { it.packageName in group.packages }.toSet())
            AppNodeRouting.validateSharedUidTargets(all, mapOf(group.uid!! to group.packages), "main-node")
        }
    }

    @Test
    fun unrelatedMainNodeChangesDoNotAlterTheAssignedSharedUidOutlet() {
        val result = plan()
        listOf("original-main", "replacement-main", "extra-node").forEach { mainNodeId ->
            val active = activeBindings(result, "ALLOW_LIST")
            AppNodeRouting.validateSharedUidTargets(active, mapOf(group.uid!! to group.packages), mainNodeId)
            assertEquals(expectedGroup("extra-node"), active.filter { it.packageName in group.packages }.toSet())
        }
    }

    @Test
    fun planCarriesTheExactOriginalSnapshotForAtomicSaveWithoutMutatingIt() {
        val originalBindings = mutableListOf(unrelated)
        val originalProxy = proxyPackages.toMutableSet()
        val originalBypass = bypassPackages.toMutableSet()
        val result = plan(bindings = originalBindings, proxy = originalProxy, bypass = originalBypass)
        assertEquals(listOf(unrelated), result.expectedBindings)
        assertEquals("ALLOW_LIST", result.expectedMode)
        assertEquals(proxyPackages, result.expectedProxyPackages)
        assertEquals(bypassPackages, result.expectedBypassPackages)
        assertEquals(listOf(unrelated), originalBindings)
        assertEquals(proxyPackages, originalProxy)
        assertEquals(bypassPackages, originalBypass)
    }

    @Test
    fun deletingEitherSiblingRemovesTheWholeGroupAndPreservesCaptureScope() {
        modes.forEach { mode ->
            val assigned = plan(mode = mode)
            val result = plan(edit(AppNodeEditOperation.DELETE).copy(packageName = sibling),
                bindings = assigned.bindings, mode = mode,
                proxy = assigned.proxyPackages, bypass = assigned.bypassPackages)
            assertEquals(listOf(unrelated), result.bindings)
            assertEquals(assigned.proxyPackages, result.proxyPackages)
            assertEquals(assigned.bypassPackages, result.bypassPackages)
            assertFalse(activeBindings(result, mode).any { it.packageName in group.packages })
        }
    }

    @Test
    fun disablingEitherSiblingDisablesTheWholeGroupAndPreservesCaptureScope() {
        modes.forEach { mode ->
            val assigned = plan(mode = mode)
            val result = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false).copy(packageName = sibling),
                bindings = assigned.bindings, mode = mode,
                proxy = assigned.proxyPackages, bypass = assigned.bypassPackages)
            assertEquals(expectedGroup("extra-node", false), groupBindings(result))
            assertEquals(assigned.proxyPackages, result.proxyPackages)
            assertEquals(assigned.bypassPackages, result.bypassPackages)
            assertEquals(listOf(unrelated), result.bindings.filter { it.packageName !in group.packages })
            assertFalse(activeBindings(result, mode).any { it.packageName in group.packages })
        }
    }

    @Test
    fun changingOneMembersNodeUnifiesAllMembersAndRemovesOldConflictingBindings() {
        val stale = listOf(unrelated, AppNodeBinding(first, "old-node"),
            AppNodeBinding(sibling, "different-node"), AppNodeBinding(first, "duplicate-node"))
        val result = plan(bindings = stale)
        assertEquals(expectedGroup("extra-node"), groupBindings(result))
        assertEquals(3, result.bindings.size)
        assertEquals(listOf(unrelated), result.bindings.filter { it.packageName !in group.packages })
    }

    @Test
    fun changingNodeOnDisabledGroupDoesNotEnableItOrWidenCapture() {
        val result = plan(bindings = listOf(unrelated) + expectedGroup("old-node", false))
        assertEquals(expectedGroup("extra-node", false), groupBindings(result))
        assertEquals(proxyPackages, result.proxyPackages)
        assertEquals(bypassPackages, result.bypassPackages)
    }

    @Test
    fun changingAnUncapturedRulesNodeKeepsTheEntireUidOutsideCapture() {
        listOf("ALLOW_LIST", "DISALLOW_LIST").forEach { mode ->
            val proxy = setOf(unrelated.packageName)
            val bypass = group.packages + bypassed
            val result = plan(bindings = listOf(unrelated) + expectedGroup("old-node"),
                mode = mode, proxy = proxy, bypass = bypass)
            assertEquals(expectedGroup("extra-node"), groupBindings(result))
            assertEquals(proxy, result.proxyPackages)
            assertEquals(bypass, result.bypassPackages)
            assertFalse(activeBindings(result, mode).any { it.packageName in group.packages })
        }
    }

    @Test
    fun enablingAnUncapturedRuleDoesNotOverrideTheUsersCaptureScope() {
        listOf("ALLOW_LIST", "DISALLOW_LIST").forEach { mode ->
            val proxy = setOf(unrelated.packageName)
            val bypass = group.packages + bypassed
            val result = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true),
                bindings = listOf(unrelated) + expectedGroup("extra-node", false),
                mode = mode, proxy = proxy, bypass = bypass)
            assertEquals(expectedGroup("extra-node"), groupBindings(result))
            assertEquals(proxy, result.proxyPackages)
            assertEquals(bypass, result.bypassPackages)
            assertFalse(activeBindings(result, mode).any { it.packageName in group.packages })
        }
    }

    @Test
    fun enablingOneMemberEnablesTheWholeGroupWithinOnlyTheCurrentMode() {
        modes.forEach { mode ->
            val result = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true),
                bindings = listOf(unrelated, AppNodeBinding(first, "extra-node", false)), mode = mode)
            assertEquals(expectedGroup("extra-node"), groupBindings(result))
            assertEquals(mode, assignedScopes.getValue(mode).first, result.proxyPackages)
            assertEquals(mode, assignedScopes.getValue(mode).second, result.bypassPackages)
            assertEquals(expectedGroup("extra-node"),
                activeBindings(result, mode).filter { it.packageName in group.packages }.toSet())
        }
    }

    @Test
    fun consentedAllowListGroupSurvivesLaterAutomaticSelectionWithoutClearingOtherExclusions() {
        val installed = group.packages + unrelated.packageName + bypassed
        val exclusions = setOf(sibling, bypassed)
        val results = listOf(
            plan(),
            plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true),
                bindings = listOf(unrelated, AppNodeBinding(first, "extra-node", false)))
        )
        results.forEach { result ->
            assertEquals(group.packages, result.proxyAutoInclusions)
            val savedExclusions = exclusions - result.proxyAutoInclusions
            assertEquals(setOf(bypassed), savedExclusions)
            val autoSelected = AutoProxySelectionPolicy.select(installed, result.proxyPackages,
                savedExclusions, extraPackageGroups = listOf(installed.toList()))
            assertEquals(setOf(first, sibling, unrelated.packageName), autoSelected)
            assertFalse(bypassed in autoSelected)
            AppNodeRouting.validateSharedUidTargets(
                AppNodeRouting.activeBindings(result.bindings, "ALLOW_LIST", autoSelected),
                mapOf(group.uid!! to group.packages), "main-node")
        }
    }

    @Test
    fun otherModesCleanupAndUncapturedEditsDoNotChangeAutomaticSelectionExclusions() {
        val active = listOf(unrelated) + expectedGroup("extra-node")
        val disabled = listOf(unrelated) + expectedGroup("extra-node", false)
        listOf("ALL", "DISALLOW_LIST").forEach { mode ->
            assertTrue(mode, plan(mode = mode).proxyAutoInclusions.isEmpty())
            assertTrue(mode, plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true),
                bindings = disabled, mode = mode).proxyAutoInclusions.isEmpty())
        }
        modes.forEach { mode ->
            assertTrue(mode, plan(edit(AppNodeEditOperation.DELETE), bindings = active,
                mode = mode).proxyAutoInclusions.isEmpty())
            assertTrue(mode, plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false),
                bindings = active, mode = mode).proxyAutoInclusions.isEmpty())
            assertTrue(mode, plan(bindings = disabled, mode = mode).proxyAutoInclusions.isEmpty())
        }
        val uncaptured = setOf(unrelated.packageName)
        assertTrue(plan(bindings = active, proxy = uncaptured).proxyAutoInclusions.isEmpty())
        assertTrue(plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true),
            bindings = disabled, proxy = uncaptured).proxyAutoInclusions.isEmpty())
    }

    @Test
    fun disablingConflictingLegacyRulesPreservesEachExistingNodeForLaterReview() {
        val existing = listOf(unrelated, AppNodeBinding(first, "first-old-node"),
            AppNodeBinding(sibling, "sibling-old-node"))
        val result = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false), bindings = existing)
        assertEquals(setOf(AppNodeBinding(first, "first-old-node", false),
            AppNodeBinding(sibling, "sibling-old-node", false)), groupBindings(result))
        assertEquals(listOf(unrelated), result.bindings.filter { it.packageName !in group.packages })
        assertEquals(proxyPackages, result.proxyPackages)
        assertEquals(bypassPackages, result.bypassPackages)
        assertEquals(result.bindings, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(result.bindings)))
    }

    @Test
    fun changedUidOrMembershipOrProtectionSinceConfirmationRequiresFreshConsent() {
        val changedGroups = listOf(
            group.copy(uid = 12002),
            group.copy(packages = group.packages + "org.example.video.newhelper"),
            group.copy(packages = setOf(first)),
            group.copy(blockedReason = "protected identity")
        )
        changedGroups.forEach { changed ->
            AppNodeEditOperation.entries.forEach { operation ->
                assertThrows(IllegalArgumentException::class.java) {
                    plan(edit(operation, enabled = true), current = changed,
                        bindings = listOf(AppNodeBinding(first, "old-node")))
                }
            }
        }
    }

    @Test
    fun renamedDisplayLabelDoesNotInvalidateUnchangedUidConsent() {
        val confirmed = group.copy(labels = mapOf(first to "Old name"))
        val current = group.copy(labels = mapOf(first to "New name"))
        assertEquals(expectedGroup("extra-node"), groupBindings(plan(edit(confirmed = confirmed), current)))
    }

    @Test
    fun protectedCoreSystemAndSelfGroupsRejectAssignAndEnableButAllowCleanup() {
        listOf("core UID", "system shared UID", "RRBOX self UID").forEach { reason ->
            val protected = group.copy(blockedReason = reason)
            val existing = listOf(unrelated) + expectedGroup("old-node")
            assertThrows(IllegalArgumentException::class.java) {
                plan(edit(confirmed = protected), current = protected, bindings = existing)
            }
            assertThrows(IllegalArgumentException::class.java) {
                plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true, confirmed = protected),
                    current = protected, bindings = existing)
            }
            val disabled = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false, confirmed = protected),
                current = protected, bindings = existing)
            assertEquals(expectedGroup("old-node", false), groupBindings(disabled))
            assertEquals(proxyPackages, disabled.proxyPackages)
            assertEquals(bypassPackages, disabled.bypassPackages)
            val deleted = plan(edit(AppNodeEditOperation.DELETE, confirmed = protected),
                current = protected, bindings = existing)
            assertEquals(listOf(unrelated), deleted.bindings)
            assertEquals(proxyPackages, deleted.proxyPackages)
            assertEquals(bypassPackages, deleted.bypassPackages)
        }
    }

    @Test
    fun protectedDisabledRuleCannotBeReassignedAsAWayToAvoidTheProtectionCheck() {
        val protected = group.copy(blockedReason = "protected identity")
        assertThrows(IllegalArgumentException::class.java) {
            plan(edit(confirmed = protected), current = protected,
                bindings = listOf(AppNodeBinding(first, "old-node", false)))
        }
    }

    @Test
    fun cleaningLegacyRulesDoesNotCreateUnencodableCoreOrSelfSiblingBindings() {
        listOf(1000 to "android", 12045 to "com.rr.client").forEach { (uid, forbiddenSibling) ->
            val packages = setOf(first, forbiddenSibling)
            val protected = AppNodeUidGroup(uid, packages,
                blockedReason = AppNodeUidGroupPolicy.blockedReason(uid, packages, 12045, "com.rr.client"))
            val existing = listOf(unrelated, AppNodeBinding(first, "old-node"))
            val disabled = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false, confirmed = protected),
                current = protected, bindings = existing)
            assertEquals(listOf(unrelated, AppNodeBinding(first, "old-node", false)), disabled.bindings)
            assertFalse(disabled.bindings.any { it.packageName == forbiddenSibling })
            assertEquals(disabled.bindings, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(disabled.bindings)))
            assertEquals(proxyPackages, disabled.proxyPackages)
            assertEquals(bypassPackages, disabled.bypassPackages)

            val deleted = plan(edit(AppNodeEditOperation.DELETE, confirmed = protected),
                current = protected, bindings = disabled.bindings)
            assertEquals(listOf(unrelated), deleted.bindings)
            assertEquals(deleted.bindings, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(deleted.bindings)))
            assertEquals(proxyPackages, deleted.proxyPackages)
            assertEquals(bypassPackages, deleted.bypassPackages)
        }
    }

    @Test
    fun savedBindingOrderRoundTripsWithoutInvalidatingTheNextAtomicEdit() {
        val last = AppNodeBinding("org.example.zulu", "zulu-node")
        val assigned = plan(bindings = listOf(last, unrelated))
        val restored = AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(assigned.bindings))
        assertEquals(assigned.bindings, restored)
        val disabled = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false),
            bindings = restored, proxy = assigned.proxyPackages, bypass = assigned.bypassPackages)
        assertEquals(assigned.bindings, disabled.expectedBindings)
        assertEquals(disabled.bindings, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(disabled.bindings)))
        val deleted = plan(edit(AppNodeEditOperation.DELETE), bindings = disabled.bindings,
            proxy = disabled.proxyPackages, bypass = disabled.bypassPackages)
        assertEquals(disabled.bindings, deleted.expectedBindings)
        assertEquals(deleted.bindings, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(deleted.bindings)))
    }

    @Test
    fun uninstalledRuleCanBeDeletedOrDisabledWithoutAnInstalledUid() {
        val uninstalled = AppNodeUidGroup(null, setOf(first))
        val existing = listOf(unrelated, AppNodeBinding(first, "old-node"))
        val deleted = plan(edit(AppNodeEditOperation.DELETE, confirmed = uninstalled),
            current = uninstalled, bindings = existing)
        assertEquals(listOf(unrelated), deleted.bindings)
        assertEquals(proxyPackages, deleted.proxyPackages)
        assertEquals(bypassPackages, deleted.bypassPackages)
        val disabled = plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = false, confirmed = uninstalled),
            current = uninstalled, bindings = existing)
        assertEquals(AppNodeBinding(first, "old-node", false), disabled.bindings.single { it.packageName == first })
    }

    @Test
    fun uninstalledRuleCannotBeAssignedOrEnabled() {
        val uninstalled = AppNodeUidGroup(null, setOf(first))
        assertThrows(IllegalArgumentException::class.java) {
            plan(edit(confirmed = uninstalled), current = uninstalled)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true, confirmed = uninstalled),
                current = uninstalled, bindings = listOf(AppNodeBinding(first, "old-node", false)))
        }
    }

    @Test
    fun missingMembershipEmptyGroupUnknownModeAndBlankNodeAreRejected() {
        listOf(group.copy(packages = setOf(sibling)), group.copy(packages = emptySet())).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                plan(edit(confirmed = invalid), current = invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { plan(mode = "unexpected") }
        listOf(null, "", "  ").forEach { invalidNode ->
            assertThrows(IllegalArgumentException::class.java) { plan(edit(nodeId = invalidNode)) }
        }
    }

    @Test
    fun enablingWithoutAnExistingRuleOrAnExplicitStateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            plan(edit(AppNodeEditOperation.SET_ENABLED, enabled = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan(edit(AppNodeEditOperation.SET_ENABLED), bindings = listOf(AppNodeBinding(first, "old-node")))
        }
    }

    @Test
    fun deletingAlreadyMissingRuleIsIdempotent() {
        val once = plan(edit(AppNodeEditOperation.DELETE))
        val twice = plan(edit(AppNodeEditOperation.DELETE), bindings = once.bindings,
            proxy = once.proxyPackages, bypass = once.bypassPackages)
        assertEquals(listOf(unrelated), twice.bindings)
        assertEquals(proxyPackages, twice.proxyPackages)
        assertEquals(bypassPackages, twice.bypassPackages)
        assertTrue(groupBindings(twice).isEmpty())
    }
}

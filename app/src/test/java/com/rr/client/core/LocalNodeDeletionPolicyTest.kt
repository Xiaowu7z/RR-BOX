package com.rr.client.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNodeDeletionPolicyTest {
    @Test fun disconnectedAllowsDeletion() {
        assertTrue(LocalNodeDeletionPolicy.canDelete("node-a", null, false))
    }

    @Test fun runningNodeIsProtected() {
        assertFalse(LocalNodeDeletionPolicy.canDelete("node-a", "node-a", true))
    }

    @Test fun unrelatedNodeCanBeDeletedWhileVpnRuns() {
        assertTrue(LocalNodeDeletionPolicy.canDelete("node-b", "node-a", true))
    }

    @Test fun unknownRunningNodeFailsClosed() {
        assertFalse(LocalNodeDeletionPolicy.canDelete("node-b", null, true))
    }

    @Test fun everyConcurrentExitIsProtectedButUnrelatedNodesRemainRemovable() {
        val runtime = setOf("la-main", "hk-telegram")
        assertFalse(LocalNodeDeletionPolicy.canDelete("la-main", runtime, true))
        assertFalse(LocalNodeDeletionPolicy.canDelete("hk-telegram", runtime, true))
        assertTrue(LocalNodeDeletionPolicy.canDelete("jp-unused", runtime, true))
        assertTrue(LocalNodeDeletionPolicy.canDelete("hk-telegram", runtime, false))
    }

    @Test fun incompleteConcurrentExitIdentityFailsClosed() {
        assertFalse(LocalNodeDeletionPolicy.canDelete("node-b", emptySet(), true))
        assertFalse(LocalNodeDeletionPolicy.canDelete("node-b", setOf("la-main", ""), true))
    }
}

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
}

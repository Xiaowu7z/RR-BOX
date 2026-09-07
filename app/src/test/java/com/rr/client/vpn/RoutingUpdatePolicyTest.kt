package com.rr.client.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingUpdatePolicyTest {
    @Test fun currentConnectedSessionCanApplyRules() {
        assertTrue(RoutingUpdatePolicy.mayApply(true, "a", "a", 4, 4))
    }

    @Test fun manualDisconnectWinsOverAlreadyQueuedUpdate() {
        assertFalse(RoutingUpdatePolicy.mayApply(false, "a", "a", 4, 4))
    }

    @Test fun updateCannotSwitchBackAfterUserChangesNode() {
        assertFalse(RoutingUpdatePolicy.mayApply(true, "a", "b", 4, 5))
    }

    @Test fun reconnectingSameNodeStillInvalidatesOldUpdate() {
        assertFalse(RoutingUpdatePolicy.mayApply(true, "a", "a", 4, 6))
    }

    @Test fun missingSessionDoesNotStartVpn() {
        assertFalse(RoutingUpdatePolicy.mayApply(true, null, null, -1, -1))
        assertFalse(RoutingUpdatePolicy.mayApply(true, "", "", 0, 0))
    }
}

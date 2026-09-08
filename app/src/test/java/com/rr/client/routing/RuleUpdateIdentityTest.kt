package com.rr.client.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleUpdateIdentityTest {
    @Test fun lateCompletionCannotCancelRetryOfSameStoredRules() {
        assertFalse(RuleUpdateIdentity.owns(42, "same-generation", 41, "same-generation"))
        assertTrue(RuleUpdateIdentity.owns(42, "same-generation", 42, "same-generation"))
    }

    @Test fun completedOperationCannotFinishTwiceOrClaimAnotherCandidate() {
        assertFalse(RuleUpdateIdentity.owns(42, null, 42, "old"))
        assertFalse(RuleUpdateIdentity.owns(43, "new", 42, "old"))
    }

    @Test fun downloadWithoutCandidateCannotBeClaimedByPreviousCallback() {
        assertFalse(RuleUpdateIdentity.owns(43, null, 42, "same-generation"))
    }
}

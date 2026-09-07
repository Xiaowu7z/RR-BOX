package com.rr.client.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetRuntimePolicyTest {
    private fun config(site: String, ip: String) =
        """{"route":{"rule_set":[{"path":"$site"},{"path":"$ip"}]}}"""

    @Test fun identicalDownloadWithCurrentRuntimeNeedsNoRestart() {
        assertFalse(RuleSetRuntimePolicy.needsReload(config("/g2/site", "/g2/ip"), "/g2/site", "/g2/ip"))
    }
    @Test fun recoveredRulePairMustReplaceRuntimeReferencingDamagedGeneration() {
        assertTrue(RuleSetRuntimePolicy.needsReload(config("/g2/site", "/g2/ip"), "/g1/site", "/g1/ip"))
    }
    @Test fun runtimeWithoutBinaryRulesMustApplyNowAvailablePair() {
        assertTrue(RuleSetRuntimePolicy.needsReload("{}", "/g1/site", "/g1/ip"))
        assertTrue(RuleSetRuntimePolicy.needsReload(null, "/g1/site", "/g1/ip"))
    }
}

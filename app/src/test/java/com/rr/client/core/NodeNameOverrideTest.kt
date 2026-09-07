package com.rr.client.core

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class NodeNameOverrideTest {
    private val base = ProxyNode("n1", "JP", ProtocolType.ANYTLS, "example.com", 443,
        uuidOrPassword = "old", rawJson = """{"type":"anytls","tag":"JP","password":"old","extra_field":42}""")
    @Test fun nameOnlyDoesNotFreezeSubscriptionPassword() {
        val override = NodeOverridePatcher.renameOverride(base, null, " 我的日本 ")
        val fresh = base.copy(uuidOrPassword = "new",
            rawJson = """{"type":"anytls","tag":"JP","password":"new","extra_field":43}""")
        val resolved = NodeOverridePatcher.resolve(fresh, override)
        assertEquals("我的日本", resolved.tag)
        assertEquals("new", resolved.uuidOrPassword)
        assertTrue(resolved.rawJson.contains("43"))
    }
    @Test fun fullParameterOverrideIsNotDiscardedByRename() {
        val full = NodeOverridePatcher.apply(base, base.copy(serverPort = 8443))
        val renamed = NodeOverridePatcher.renameOverride(base, full, "Mine")
        assertFalse(renamed.nameOverrideOnly)
        assertEquals(8443, NodeOverridePatcher.resolve(base, renamed).serverPort)
    }
    @Test fun identifiesLegacyNameOnlyOverrideForMigration() {
        val legacy = NodeOverridePatcher.apply(base, base.copy(tag = "Mine"))
        assertTrue(NodeOverridePatcher.isNameOnlyEdit(base, legacy))
        assertFalse(NodeOverridePatcher.isNameOnlyEdit(base, legacy.copy(serverPort = 80)))
    }
    @Test fun clearingOverrideRestoresSubscriptionName() {
        assertEquals(base, NodeOverridePatcher.resolve(base, null))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBlankName() {
        NodeOverridePatcher.renameOverride(base, null, "  ")
    }
}

package com.rr.client.core

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class NodeIdentityTest {
    private val base = ProxyNode("one", "JP", ProtocolType.ANYTLS, "example.com", 443,
        uuidOrPassword = "secret", rawJson = """{"type":"anytls","tag":"JP","password":"secret"}""")
    @Test fun renameAndJsonKeyOrderDoNotDuplicateNode() {
        val renamed = base.copy(id = "two", tag = "new", profileName = "different",
            rawJson = """{ "password":"secret", "tag":"new", "type":"anytls" }""")
        assertEquals(NodeIdentity.key(base), NodeIdentity.key(renamed))
    }
    @Test fun transportDifferencesRemainDistinct() {
        assertNotEquals(NodeIdentity.key(base), NodeIdentity.key(base.copy(path = "/other")))
    }
    @Test fun rawUnknownFieldsRemainDistinct() {
        assertNotEquals(NodeIdentity.key(base), NodeIdentity.key(base.copy(
            rawJson = """{"type":"anytls","password":"secret","min_idle_session":2}""")))
    }
    @Test fun credentialsRemainDistinct() {
        assertNotEquals(NodeIdentity.key(base), NodeIdentity.key(base.copy(uuidOrPassword = "different")))
    }
}

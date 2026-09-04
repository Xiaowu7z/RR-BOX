package com.rr.client.core

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeOverridePatcherRawTest {
    @Test
    fun changedRawOutboundIsAuthoritative() {
        val original = ProxyNode(
            id = "n1",
            tag = "old",
            type = ProtocolType.ANYTLS,
            server = "old.example.com",
            serverPort = 443,
            uuidOrPassword = "old-pass",
            rawJson = """{"type":"anytls","tag":"old","server":"old.example.com","server_port":443,"password":"old-pass"}"""
        )
        val edited = original.copy(
            tag = "new",
            server = "new.example.com",
            uuidOrPassword = "new-pass",
            rawJson = """{"type":"anytls","tag":"new","server":"new.example.com","server_port":443,"password":"new-pass","idle_session_check_interval":"30s"}"""
        )

        val patched = NodeOverridePatcher.apply(original, edited)
        assertEquals(edited.rawJson, patched.rawJson)
        assertEquals("new.example.com", patched.server)
        assertTrue(patched.rawJson.contains("idle_session_check_interval"))
    }
}

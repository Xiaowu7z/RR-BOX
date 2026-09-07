package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class TlsVerificationPolicyTest {
    @Test fun hy2AndTuicVerifyCertificatesByDefault() {
        listOf(ProtocolType.HYSTERIA2, ProtocolType.TUIC_V5).forEach { type ->
            val node = ProxyNode("n", "Test", type, "example.com", 443, "credential")
            val config = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(node, listOf(node), emptyList())).asJsonObject
            val tls = config.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("tls")
            assertFalse(tls.get("insecure").asBoolean)
        }
    }
    @Test fun explicitlyConfiguredSelfSignedNodeCanOptIn() {
        val node = ProxyNode("n", "Test", ProtocolType.HYSTERIA2, "example.com", 443, "credential", allowInsecure = true)
        val config = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(node, listOf(node), emptyList())).asJsonObject
        assertTrue(config.getAsJsonArray("outbounds")[0].asJsonObject.getAsJsonObject("tls").get("insecure").asBoolean)
    }
}

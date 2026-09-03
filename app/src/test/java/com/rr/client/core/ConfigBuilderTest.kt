package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    @Test
    fun directDnsDoesNotDetourThroughDirectOutbound() {
        val node = ProxyNode(
            id = "test",
            tag = "Reality test",
            type = ProtocolType.VLESS_REALITY,
            server = "192.0.2.1",
            serverPort = 443,
            uuidOrPassword = "00000000-0000-4000-8000-000000000000",
            flow = "xtls-rprx-vision",
            realityPublicKey = "test-public-key",
            realityShortId = "0123456789abcdef",
            sni = "www.example.com",
            tlsEnabled = true
        )

        val root = JsonParser.parseString(
            ConfigBuilder.buildSingBoxConfig(
                selectedNode = node,
                allNodes = listOf(node),
                appRoutes = emptyList(),
                smartRouting = false
            )
        ).asJsonObject

        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        val directDns = servers.first { it.asJsonObject.get("tag").asString == "dns-direct" }.asJsonObject
        val remoteDns = servers.first { it.asJsonObject.get("tag").asString == "dns-remote" }.asJsonObject

        assertFalse("A direct DNS transport must not detour through the empty direct outbound", directDns.has("detour"))
        assertEquals("proxy", remoteDns.get("detour").asString)

        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "proxy" })
        assertTrue(outbounds.any { it.asJsonObject.get("tag").asString == "direct" })
    }
}

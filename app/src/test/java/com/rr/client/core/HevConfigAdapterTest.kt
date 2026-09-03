package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevConfigAdapterTest {
    private fun node() = ProxyNode(
        id = "hev-test",
        tag = "Reality HEV",
        type = ProtocolType.VLESS_REALITY,
        server = "192.0.2.10",
        serverPort = 443,
        uuidOrPassword = "00000000-0000-4000-8000-000000000000",
        flow = "xtls-rprx-vision",
        realityPublicKey = "test-public-key",
        realityShortId = "0123456789abcdef",
        sni = "www.example.com",
        tlsEnabled = true
    )

    private fun stable(mode: String, packages: Set<String> = emptySet()): String {
        val node = node()
        return ConfigBuilder.buildSingBoxConfig(
            selectedNode = node,
            allNodes = listOf(node),
            appRoutes = emptyList(),
            smartRouting = false,
            perAppMode = mode,
            selectedPackages = packages,
            fastForwarding = false
        )
    }

    @Test
    fun hevReplacesOnlyTunInboundWithLoopbackSocks() {
        val source = JsonParser.parseString(stable(PerAppPolicyResolver.MODE_ALL)).asJsonObject
        val runtime = HevConfigAdapter.adapt(source.toString())
        val adapted = JsonParser.parseString(runtime.configJson).asJsonObject

        val inbound = adapted.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("socks", inbound.get("type").asString)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals(HevConfigAdapter.SOCKS_PORT, inbound.get("listen_port").asInt)
        assertFalse(runtime.configJson.contains("\"type\": \"tun\""))

        assertEquals(source.get("outbounds"), adapted.get("outbounds"))
        assertEquals(source.get("dns"), adapted.get("dns"))
        assertEquals(source.get("route"), adapted.get("route"))
    }

    @Test
    fun systemAllowListSelfEntryIsRemovedForHevBridge() {
        val runtime = HevConfigAdapter.adapt(
            stable(
                PerAppPolicyResolver.MODE_ALLOW_LIST,
                setOf("com.android.chrome", "org.telegram.messenger")
            )
        )

        assertEquals(
            listOf("com.android.chrome", "org.telegram.messenger"),
            runtime.perAppPolicy.allowedPackages
        )
        assertTrue(runtime.perAppPolicy.disallowedPackages.isEmpty())
        assertFalse(runtime.perAppPolicy.allowedPackages.contains("com.rr.client"))
    }

    @Test
    fun bypassPolicySurvivesHevAdaptation() {
        val runtime = HevConfigAdapter.adapt(
            stable(
                PerAppPolicyResolver.MODE_DISALLOW_LIST,
                setOf("com.example.direct")
            )
        )

        assertTrue(runtime.perAppPolicy.allowedPackages.isEmpty())
        assertEquals(listOf("com.example.direct"), runtime.perAppPolicy.disallowedPackages)
    }

    @Test
    fun allModeHasNoExplicitSelectedAppsForHev() {
        val runtime = HevConfigAdapter.adapt(stable(PerAppPolicyResolver.MODE_ALL))
        assertTrue(runtime.perAppPolicy.allowedPackages.isEmpty())
        assertTrue(runtime.perAppPolicy.disallowedPackages.isEmpty())
    }
}

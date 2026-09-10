package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.vpn.HevTunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun hevReplacesTunWithSocksAndAddsOnlyItsInternalResolverRule() {
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
        val route = adapted.getAsJsonObject("route").deepCopy()
        val resolver = route.getAsJsonArray("rules").remove(0).asJsonObject
        assertEquals("hijack-dns", resolver.get("action").asString)
        assertEquals(listOf(HevConfigAdapter.SOCKS_TAG), resolver.getAsJsonArray("inbound").map { it.asString })
        assertEquals(listOf("${HevTunnelConfig.DNS_ADDRESS}/32"), resolver.getAsJsonArray("ip_cidr").map { it.asString })
        assertEquals(53, resolver.get("port").asInt)
        assertEquals(listOf("tcp", "udp"), resolver.getAsJsonArray("network").map { it.asString })
        assertEquals(source.get("route"), route)
    }

    @Test
    fun resolverWorksWithoutSmartRoutingDnsInterceptionOrSniffing() {
        val node = node()
        val source = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
            selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(),
            smartRouting = false, enableDnsRules = false, fastForwarding = true
        )).asJsonObject
        assertTrue(source.getAsJsonObject("route").getAsJsonArray("rules").isEmpty)
        val runtime = JsonParser.parseString(HevConfigAdapter.adapt(source.toString()).configJson).asJsonObject
        val rules = runtime.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(1, rules.size())
        assertEquals("hijack-dns", rules[0].asJsonObject.get("action").asString)
        assertEquals(source.get("dns"), runtime.get("dns"))
        assertEquals(source.get("outbounds"), runtime.get("outbounds"))
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

    @Test
    fun explicitEmptyOrSelfOnlyAllowListNeverBecomesAllApplications() {
        val unusableLists = listOf(
            emptyList(),
            listOf("", " "),
            listOf("com.rr.client"),
            listOf(" ", " com.rr.client ")
        )
        unusableLists.forEach { packages ->
            val source = JsonParser.parseString(stable(PerAppPolicyResolver.MODE_ALL)).asJsonObject
            source.getAsJsonArray("inbounds")[0].asJsonObject.add(
                "include_package", JsonArray().apply { packages.forEach(::add) }
            )

            val error = assertThrows(IllegalArgumentException::class.java) {
                HevConfigAdapter.adapt(source.toString())
            }
            assertEquals("HEV 仅选中代理模式至少需要选择 1 个其他应用", error.message)
        }
    }
}

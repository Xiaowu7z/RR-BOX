package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {
    private fun node() = ProxyNode(
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

    private fun build(
        smartRouting: Boolean = false,
        paths: ChinaRuleSetManager.Paths? = null,
        mode: String = PerAppPolicyResolver.MODE_ALL,
        packages: Set<String> = emptySet()
    ): JsonObject {
        val node = node()
        return JsonParser.parseString(
            ConfigBuilder.buildSingBoxConfig(
                selectedNode = node,
                allNodes = listOf(node),
                appRoutes = emptyList(),
                smartRouting = smartRouting,
                perAppMode = mode,
                selectedPackages = packages,
                ruleSets = paths
            )
        ).asJsonObject
    }

    private fun tun(root: JsonObject): JsonObject = root.getAsJsonArray("inbounds")[0].asJsonObject

    @Test
    fun directDnsDoesNotDetourThroughDirectOutbound() {
        val root = build(smartRouting = false)
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        val directDns = servers.first { it.asJsonObject.get("tag").asString == "dns-direct" }.asJsonObject
        val remoteDns = servers.first { it.asJsonObject.get("tag").asString == "dns-remote" }.asJsonObject

        assertFalse(directDns.has("detour"))
        assertEquals("proxy", remoteDns.get("detour").asString)
        assertEquals(2, root.getAsJsonArray("outbounds").size())
    }

    @Test
    fun allModeDoesNotInstallAnyAndroidPackageFilter() {
        val inbound = tun(build(mode = PerAppPolicyResolver.MODE_ALL, packages = setOf("com.example")))
        assertFalse(inbound.has("include_package"))
        assertFalse(inbound.has("exclude_package"))
    }

    @Test
    fun allowListIncludesSelectedAppsAndRRBOXItself() {
        val inbound = tun(
            build(
                mode = PerAppPolicyResolver.MODE_ALLOW_LIST,
                packages = setOf("org.telegram.messenger", "com.twitter.android")
            )
        )
        assertTrue(inbound.has("include_package"))
        assertFalse(inbound.has("exclude_package"))
        val include = inbound.getAsJsonArray("include_package").map { it.asString }.toSet()
        assertEquals(
            setOf("com.rr.client", "org.telegram.messenger", "com.twitter.android"),
            include
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAllowListIsRejected() {
        build(mode = PerAppPolicyResolver.MODE_ALLOW_LIST, packages = emptySet())
    }

    @Test
    fun bypassListWritesOnlyExcludePackageAndNeverExcludesRRBOX() {
        val inbound = tun(
            build(
                mode = PerAppPolicyResolver.MODE_DISALLOW_LIST,
                packages = setOf("com.example.direct", "com.rr.client")
            )
        )
        assertFalse(inbound.has("include_package"))
        val exclude = inbound.getAsJsonArray("exclude_package").map { it.asString }.toSet()
        assertEquals(setOf("com.example.direct"), exclude)
    }

    @Test
    fun smartRoutingUsesTwoStableBinaryRuleSets() {
        val paths = ChinaRuleSetManager.Paths(
            geositeChina = "/rules/geosite-cn.srs",
            geoipChina = "/rules/geoip-cn.srs"
        )
        val root = build(smartRouting = true, paths = paths)
        val route = root.getAsJsonObject("route")
        val sets = route.getAsJsonArray("rule_set")

        assertEquals(2, sets.size())
        assertTrue(sets.all { it.asJsonObject.get("format").asString == "binary" })
        assertTrue(sets.all { it.asJsonObject.get("type").asString == "local" })

        val rules = route.getAsJsonArray("rules")
        assertTrue(rules.any {
            it.asJsonObject.getAsJsonArray("rule_set")?.any { tag ->
                tag.asString == "geosite-geolocation-cn"
            } == true
        })
        assertTrue(rules.any {
            it.asJsonObject.getAsJsonArray("rule_set")?.any { tag ->
                tag.asString == "geoip-cn"
            } == true
        })
        assertFalse(rules.any { it.asJsonObject.get("type")?.asString == "logical" })
    }

    @Test
    fun smartRoutingFallsBackToCnSuffixWithoutBinaryFiles() {
        val root = build(smartRouting = true, paths = null)
        val route = root.getAsJsonObject("route")
        val rules = route.getAsJsonArray("rules")

        assertFalse(route.has("rule_set"))
        assertTrue(rules.any { it.asJsonObject.has("domain_suffix") })
        assertTrue(rules.any { it.asJsonObject.get("ip_is_private")?.asBoolean == true })
    }

    @Test
    fun disablingSmartRoutingRemovesCnRules() {
        val root = build(
            smartRouting = false,
            paths = ChinaRuleSetManager.Paths("/a.srs", "/b.srs")
        )
        val route = root.getAsJsonObject("route")
        val rules = route.getAsJsonArray("rules")

        assertFalse(route.has("rule_set"))
        assertFalse(rules.any { it.asJsonObject.has("domain_suffix") })
        assertFalse(rules.any { it.asJsonObject.has("rule_set") })
        assertFalse(rules.any { it.asJsonObject.get("ip_is_private")?.asBoolean == true })
    }
}

package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        perAppMode: String = "ALL",
        selectedPackages: Set<String> = emptySet()
    ): JsonObject {
        val node = node()
        return JsonParser.parseString(
            ConfigBuilder.buildSingBoxConfig(
                selectedNode = node,
                allNodes = listOf(node),
                appRoutes = emptyList(),
                smartRouting = smartRouting,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages
            )
        ).asJsonObject
    }

    @Test
    fun directDnsDoesNotDetourThroughDirectOutbound() {
        val root = build(smartRouting = false)
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

    @Test
    fun allowListIsWrittenIntoTunIncludePackage() {
        val root = build(
            perAppMode = "ALLOW_LIST",
            selectedPackages = setOf("org.telegram.messenger", "com.twitter.android")
        )
        val tun = root.getAsJsonArray("inbounds")[0].asJsonObject
        val include = tun.getAsJsonArray("include_package").map { it.asString }.toSet()

        assertEquals(setOf("org.telegram.messenger", "com.twitter.android"), include)
        assertFalse(tun.has("exclude_package"))
    }

    @Test
    fun disallowListIsWrittenIntoTunExcludePackage() {
        val root = build(
            perAppMode = "DISALLOW_LIST",
            selectedPackages = setOf("com.example.direct")
        )
        val tun = root.getAsJsonArray("inbounds")[0].asJsonObject

        assertEquals("com.example.direct", tun.getAsJsonArray("exclude_package")[0].asString)
        assertFalse(tun.has("include_package"))
    }

    @Test
    fun allModeDoesNotSetAndroidPackageFilters() {
        val root = build(
            perAppMode = "ALL",
            selectedPackages = setOf("com.example.ignored")
        )
        val tun = root.getAsJsonArray("inbounds")[0].asJsonObject

        assertFalse(tun.has("include_package"))
        assertFalse(tun.has("exclude_package"))
    }

    @Test
    fun emptyAllowListIsRejectedInsteadOfAccidentallyProxyingEverything() {
        assertThrows(IllegalArgumentException::class.java) {
            build(perAppMode = "ALLOW_LIST", selectedPackages = emptySet())
        }
    }

    @Test
    fun smartRoutingActuallyAddsAndRemovesRules() {
        val enabled = build(smartRouting = true)
        val disabled = build(smartRouting = false)

        val enabledRules = enabled.getAsJsonObject("route").getAsJsonArray("rules")
        val disabledRules = disabled.getAsJsonObject("route").getAsJsonArray("rules")

        assertTrue(enabledRules.any { it.asJsonObject.get("ip_is_private")?.asBoolean == true })
        assertTrue(enabledRules.any { it.asJsonObject.has("domain_suffix") })
        assertFalse(disabledRules.any { it.asJsonObject.has("ip_is_private") })
        assertFalse(disabledRules.any { it.asJsonObject.has("domain_suffix") })

        val dnsRulesEnabled = enabled.getAsJsonObject("dns").getAsJsonArray("rules")
        val dnsRulesDisabled = disabled.getAsJsonObject("dns").getAsJsonArray("rules")
        assertTrue(dnsRulesEnabled.any { it.asJsonObject.has("domain_suffix") })
        assertFalse(dnsRulesDisabled.any { it.asJsonObject.has("domain_suffix") })
    }
}

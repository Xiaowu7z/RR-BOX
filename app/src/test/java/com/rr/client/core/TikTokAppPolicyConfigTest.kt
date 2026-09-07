package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class TikTokAppPolicyConfigTest {
    private fun config(smart: Boolean = true): JsonObject {
        val node = ProxyNode(
            id = "test", tag = "test", type = ProtocolType.SOCKS,
            server = "192.0.2.1", serverPort = 1080,
            rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}"""
        )
        return JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
            selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(), smartRouting = smart
        )).asJsonObject
    }

    private fun rules(root: JsonObject) = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
    private fun guard(root: JsonObject) = rules(root).first { it.get("type")?.asString == "logical" }

    @Test
    fun exactTikTokAndPluginIdentityTakesPriorityOverSharedDomesticDomains() {
        val root = config()
        val guard = guard(root)
        val packages = guard.getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("package_name").map { it.asString }
        assertEquals(listOf("com.zhiliaoapp.musically", "com.rezvorck.tiktokplugin"), packages)
        assertFalse(packages.contains("com.ss.android.ugc.aweme"))
        assertEquals("proxy", guard.get("outbound").asString)
        assertEquals("route", guard.get("action").asString)
        assertTrue(rules(root).indexOf(guard) < rules(root).indexOfFirst { it.has("domain_suffix") })
        assertTrue(rules(root).indexOf(guard) > rules(root).indexOfFirst { it.get("action")?.asString == "hijack-dns" })
    }

    @Test
    fun appGuardExcludesKnownPrivateAddressesWithoutReorderingExistingPrivateRules() {
        val root = config()
        val guard = guard(root)
        assertEquals("and", guard.get("mode").asString)
        val children = guard.getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(2, children.size)
        assertTrue(children[1].get("ip_is_private").asBoolean)
        assertTrue(children[1].get("invert").asBoolean)
        assertTrue(children.none { it.has("action") || it.has("outbound") })
        assertTrue(rules(root).any { it.get("ip_is_private")?.asBoolean == true && it.get("outbound")?.asString == "direct" })
    }

    @Test
    fun smartOffRemovesAppGuardTogetherWithDomainPolicies() {
        assertTrue(rules(config(smart = false)).none { it.has("type") || it.has("domain_suffix") })
    }

    @Test
    fun hevRetainsRulesButDoesNotInventOriginalAppIdentity() {
        val stable = config()
        val hev = JsonParser.parseString(HevConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
        assertEquals(stable.get("route"), hev.get("route"))
        assertTrue(rules(hev).any { it.has("domain_suffix") })
        val inbound = hev.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("hev-socks-in", inbound.get("tag").asString)
        assertFalse(inbound.has("package_name"))
    }
}

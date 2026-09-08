package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.RoutingPolicySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner-sensitive matching requires Android device verification; these checks cover configuration. */
class JarvisAppPolicyConfigTest {
    private val node = ProxyNode("jarvis-test", "jarvis-test", ProtocolType.SOCKS, "192.0.2.1", 1080,
        rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080}""")
    private val expectedRule = JsonParser.parseString("""{
        "package_name":["app.jarvis.assistant"],"action":"route","outbound":"direct"
    }""").asJsonObject

    private fun config(
        smart: Boolean = true,
        dns: Boolean = true,
        fast: Boolean = false,
        mode: String = PerAppPolicyResolver.MODE_ALL,
        packages: Set<String> = emptySet(),
        policy: RoutingPolicySnapshot = RoutingPolicySnapshot.bundled()
    ) = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
        node, listOf(node), emptyList(), smartRouting = smart, enableDnsRules = dns,
        fastForwarding = fast, perAppMode = mode, selectedPackages = packages, routingPolicy = policy
    )).asJsonObject

    private fun rules(root: JsonObject) = root.getAsJsonObject("route").getAsJsonArray("rules")
        .map { it.asJsonObject }

    private fun variants(stable: JsonObject) = listOf(stable,
        JsonParser.parseString(RootConfigAdapter.adapt(stable.toString()).configJson).asJsonObject,
        JsonParser.parseString(HevConfigAdapter.adapt(stable.toString()).configJson).asJsonObject)

    @Test
    fun exactAppOverrideFollowsDnsAndPrecedesRecoveryAndOtherBusinessRules() {
        for (dns in listOf(false, true)) for (fast in listOf(false, true)) {
            val stable = config(dns = dns, fast = fast)
            for (root in variants(stable)) {
                val route = rules(root)
                assertEquals(1, route.count { it == expectedRule })
                val index = route.indexOf(expectedRule)
                assertTrue(route.take(index).all {
                    it["action"]?.asString in setOf("sniff", "hijack-dns")
                })
                assertTrue(route.drop(index + 1).none { it["action"]?.asString == "hijack-dns" })
                assertTrue(index < route.indexOfFirst { it["action"]?.asString == "route-options" })
                assertTrue(index < route.indexOfFirst { it["outbound"]?.asString == "proxy" })
                assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
            }
        }
    }

    @Test
    fun smartOffRemovesTheAppOverrideForEveryEngine() {
        for (fast in listOf(false, true)) for (root in variants(config(smart = false, fast = fast))) {
            assertFalse(root.toString().contains("app.jarvis.assistant"))
            assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
            assertEquals("dns-remote", root.getAsJsonObject("dns")["final"].asString)
        }
    }

    @Test
    fun existingSchemaOneSnapshotsCannotBroadenOrRemoveTheExplicitAppPreference() {
        val policy = RoutingPolicySnapshot.parse("""{
            "schemaVersion":1,"ruleVersion":1,"publishedAt":"2026-09-08T00:00:00Z",
            "description":"Existing schema-one policy",
            "domainRules":[{"id":"foreign","destination":"PROXY","suffixes":["foreign.example"],"domains":[]}],
            "proxyPackageGroups":[["app.jarvis.assistant","app.jarvis.assistant.debug"]],
            "directIpExceptions":[]
        }""")
        val root = config(policy = policy)
        val route = rules(root)
        assertEquals(listOf(expectedRule), route.filter {
            it.has("package_name") && it["outbound"]?.asString == "direct"
        })
        val proxyPackageRule = route.single { it.has("rules") && it["outbound"]?.asString == "proxy" }
        assertTrue(route.indexOf(expectedRule) < route.indexOf(proxyPackageRule))
        assertEquals(listOf("app.jarvis.assistant", "app.jarvis.assistant.debug"),
            proxyPackageRule.getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("package_name")
                .map { it.asString })
        // The direct rule contains only one exact package match: no wildcard, app
        // inventory, shared UID assumption or additional destination condition.
        assertEquals(setOf("package_name", "action", "outbound"), expectedRule.keySet())
    }

    @Test
    fun appOverridePreservesUserSelectedCaptureScopeAndSharedDnsPolicy() {
        val modes = listOf(PerAppPolicyResolver.MODE_ALL, PerAppPolicyResolver.MODE_ALLOW_LIST,
            PerAppPolicyResolver.MODE_DISALLOW_LIST)
        for (mode in modes) {
            val stable = config(mode = mode, packages = setOf("com.example.selected"))
            val inbound = stable.getAsJsonArray("inbounds").single().asJsonObject
            val key = if (mode == PerAppPolicyResolver.MODE_ALLOW_LIST) "include_package" else "exclude_package"
            if (mode == PerAppPolicyResolver.MODE_ALL) {
                assertFalse(inbound.has("include_package")); assertFalse(inbound.has("exclude_package"))
            } else {
                val expected = if (mode == PerAppPolicyResolver.MODE_ALLOW_LIST)
                    listOf("com.example.selected", "com.rr.client") else listOf("com.example.selected")
                assertEquals(expected, inbound.getAsJsonArray(key).map { it.asString })
            }
            for (root in variants(stable)) {
                assertEquals(stable.getAsJsonObject("dns"), root.getAsJsonObject("dns"))
                assertEquals(expectedRule, rules(root).single { it == expectedRule })
            }
        }
    }
}

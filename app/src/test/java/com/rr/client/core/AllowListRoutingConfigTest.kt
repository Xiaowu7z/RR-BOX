package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.JarvisAppPolicy
import com.rr.client.routing.PerAppPolicyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** App capture must stay independent of domain routing and observability switches. */
@RunWith(Parameterized::class)
class AllowListRoutingConfigTest(
    private val engine: String,
    private val fast: Boolean,
    private val interceptDns: Boolean
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, fast={1}, interceptDns={2}")
        fun variants(): List<Array<Any>> = listOf("system", "hev", "root").flatMap { engine ->
            listOf(false, true).flatMap { fast ->
                listOf(false, true).map { dns -> arrayOf<Any>(engine, fast, dns) }
            }
        }
    }

    @Test
    fun selectedSystemAndThirdPartyAppsKeepProxyAndRemoteDnsWithSmartRoutingOff() {
        // Jarvis is deliberately hand-selected: its smart-mode DIRECT override
        // must disappear just like domestic domain/IP rules when smart is off.
        val selected = setOf("com.openai.chatgpt", "com.google.android.gms", JarvisAppPolicy.PACKAGE_NAME)
        val node = ProxyNode(
            id = "allowlist-test", tag = "Selected proxy", type = ProtocolType.SOCKS,
            server = "node.example.org", serverPort = 1080,
            rawJson = """{"type":"socks","server":"node.example.org","server_port":1080,"version":"5"}"""
        )
        val stable = ConfigBuilder.buildSingBoxConfig(
            selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(),
            smartRouting = false, enableDnsRules = interceptDns, fastForwarding = fast,
            perAppMode = PerAppPolicyResolver.MODE_ALLOW_LIST, selectedPackages = selected,
            // Existing downloaded rules must not reactivate domain routing.
            ruleSets = ChinaRuleSetManager.Paths("/existing-cn.srs", "/existing-ip.srs")
        )
        val runtimeJson = when (engine) {
            "hev" -> HevConfigAdapter.adapt(stable).also {
                assertEquals(selected, it.perAppPolicy.allowedPackages.toSet())
                assertTrue(it.perAppPolicy.disallowedPackages.isEmpty())
            }.configJson
            "root" -> RootConfigAdapter.adapt(stable).also {
                assertEquals(selected, it.perAppPolicy.allowedPackages.toSet())
                assertTrue(it.perAppPolicy.disallowedPackages.isEmpty())
            }.configJson
            else -> stable
        }
        val runtime = JsonParser.parseString(runtimeJson).asJsonObject
        val inbound = runtime.getAsJsonArray("inbounds")[0].asJsonObject
        if (engine == "system") {
            assertEquals(selected + "com.rr.client", inbound.getAsJsonArray("include_package").map { it.asString }.toSet())
        } else {
            assertFalse(inbound.has("include_package"))
        }
        assertFalse(inbound.has("exclude_package"))

        val route = runtime.getAsJsonObject("route")
        assertEquals("proxy", route.get("final").asString)
        assertFalse(route.has("rule_set"))
        val rules = route.getAsJsonArray("rules").map { it.asJsonObject }
        // A selected domestic app, public IPv4/IPv6, TCP/UDP and HTTPS all reach
        // the proxy final; no package/domain/IP/transport business exception remains.
        assertTrue(rules.all { it.get("action")?.asString in setOf("sniff", "hijack-dns") })
        assertTrue(rules.none { it.has("outbound") || it.has("package_name") || it.has("rule_set") })
        assertEquals(!fast, rules.any { it.get("action")?.asString == "sniff" })

        val dns = runtime.getAsJsonObject("dns")
        assertEquals("dns-remote", dns.get("final").asString)
        assertFalse(dns.get("reverse_mapping").asBoolean)
        val dnsRules = dns.getAsJsonArray("rules")
        assertEquals(1, dnsRules.size())
        assertEquals(listOf(node.server), dnsRules[0].asJsonObject.getAsJsonArray("domain").map { it.asString })
        assertEquals("dns-direct", dnsRules[0].asJsonObject.get("server").asString)
        val remote = dns.getAsJsonArray("servers").map { it.asJsonObject }.single { it.get("tag").asString == "dns-remote" }
        assertEquals("proxy", remote.get("detour").asString)
        assertEquals("tls", remote.get("type").asString)
        assertFalse(remote.getAsJsonObject("tls").has("insecure"))
    }
}

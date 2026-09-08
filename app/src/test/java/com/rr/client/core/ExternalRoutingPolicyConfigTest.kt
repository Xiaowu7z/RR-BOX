package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.RoutingPolicySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalRoutingPolicyConfigTest {
    private val node = ProxyNode(id = "external-policy", tag = "external-policy", type = ProtocolType.SOCKS,
        server = "node.example.com", serverPort = 1080,
        rawJson = """{"type":"socks","server":"node.example.com","server_port":1080,"version":"5"}""")

    private fun asset() = listOf(File("src/main/assets/rules/rrbox-policy.json"),
        File("app/src/main/assets/rules/rrbox-policy.json")).first { it.isFile }.readText()

    private fun custom(): RoutingPolicySnapshot = RoutingPolicySnapshot.parse("""{
      "schemaVersion":1,"ruleVersion":2026090802,"publishedAt":"2026-09-08T01:00:00Z",
      "description":"测试同一快照编译",
      "domainRules":[
        {"id":"custom-proxy","destination":"PROXY","suffixes":["overseas.example"],"domains":["login.shared.example"]},
        {"id":"custom-direct","destination":"DIRECT","suffixes":["mainland.example"],"domains":[]}
      ],
      "proxyPackageGroups":[["com.example.international"]],
      "directIpExceptions":["192.0.2.12/32","2001:db8::12/128"]
    }""")

    private fun config(policy: RoutingPolicySnapshot, smart: Boolean = true) = JsonParser.parseString(
        ConfigBuilder.buildSingBoxConfig(node, listOf(node), emptyList(), smartRouting = smart,
            ruleSets = ChinaRuleSetManager.Paths("/rules/geosite.srs", "/rules/geoip.srs"), routingPolicy = policy)
    ).asJsonObject

    @Test
    fun canonicalAssetControlsCompiledDomainRulesAndDnsInTheSameOrder() {
        val loaded = RoutingPolicySnapshot.parse(asset())
        val compiled = config(loaded)
        val route = compiled.getAsJsonObject("route").getAsJsonArray("rules")
            .map { it.asJsonObject }.filter { it.has("domain") || it.has("domain_suffix") }
        val dns = compiled.getAsJsonObject("dns").getAsJsonArray("rules")
            .map { it.asJsonObject }.drop(1).filter { it.has("domain") || it.has("domain_suffix") }
        assertEquals(loaded.domainRules.size, route.size)
        assertEquals(loaded.domainRules.size, dns.size)
        loaded.domainRules.forEachIndexed { index, policy ->
            assertEquals(policy.domains, route[index].getAsJsonArray("domain")?.map { it.asString }.orEmpty())
            assertEquals(policy.suffixes, route[index].getAsJsonArray("domain_suffix")?.map { it.asString }.orEmpty())
            assertEquals(route[index].deepCopy().apply { remove("outbound") },
                dns[index].deepCopy().apply { remove("server"); remove("action") })
        }
        if (loaded.ruleVersion == RoutingPolicySnapshot.bundled().ruleVersion) {
            assertEquals(config(RoutingPolicySnapshot.bundled()), compiled)
        }
    }

    @Test
    fun customSnapshotReplacesCompiledPoliciesAndFeedsBothDnsAndRoutes() {
        val root = config(custom())
        val route = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val dns = root.getAsJsonObject("dns").getAsJsonArray("rules").map { it.asJsonObject }
        for ((suffix, outbound, resolver) in listOf(
            Triple("overseas.example", "proxy", "dns-remote"),
            Triple("mainland.example", "direct", "dns-direct")
        )) {
            val routeRule = route.single { it.getAsJsonArray("domain_suffix")?.any { value -> value.asString == suffix } == true }
            val dnsRule = dns.single { it.getAsJsonArray("domain_suffix")?.any { value -> value.asString == suffix } == true }
            assertEquals(outbound, routeRule["outbound"].asString)
            assertEquals(resolver, dnsRule["server"].asString)
            assertEquals(routeRule.deepCopy().apply { remove("outbound") }, dnsRule.deepCopy().apply { remove("server"); remove("action") })
        }
        val json = root.toString()
        assertFalse(json.contains("bigo.sg"))
        assertFalse(json.contains("com.zhiliaoapp.musically"))
        assertTrue(json.contains("com.example.international"))
        assertTrue(json.contains("192.0.2.12/32"))
        assertTrue(json.contains("2001:db8::12/128"))
        assertEquals("node.example.com", dns.first().getAsJsonArray("domain")[0].asString)
        assertEquals("dns-direct", dns.first()["server"].asString)
        assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
    }

    @Test
    fun appGuardsAndPrivateTrafficRemainAheadOfChinaFallbackWithNewData() {
        val rules = config(custom()).getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val packageIndex = rules.indexOfFirst { it.has("rules") }
        assertTrue(packageIndex > rules.indexOfFirst { it["action"]?.asString == "hijack-dns" })
        assertEquals(JsonParser.parseString("""{"ip_is_private":true,"invert":true}"""),
            rules[packageIndex].getAsJsonArray("rules")[1])
        val domainIndex = rules.indexOfFirst { it.has("domain_suffix") }
        val ipIndex = rules.indexOfFirst { it.has("ip_cidr") }
        val privateIndex = rules.indexOfFirst { it["ip_is_private"]?.asBoolean == true }
        val chinaIndex = rules.indexOfFirst { it.has("rule_set") }
        assertTrue(packageIndex < domainIndex && domainIndex < ipIndex && ipIndex < privateIndex && privateIndex < chinaIndex)
    }

    @Test
    fun emptyOptionalListsDoNotCompileAnUnconditionalRoute() {
        val data = JsonParser.parseString(asset()).asJsonObject.apply {
            add("proxyPackageGroups", JsonArray()); add("directIpExceptions", JsonArray())
        }
        val rules = config(RoutingPolicySnapshot.parse(data.toString())).getAsJsonObject("route").getAsJsonArray("rules")
        assertFalse(rules.any { it.asJsonObject.has("ip_cidr") })
        assertFalse(rules.any { rule -> rule.asJsonObject.getAsJsonArray("rules")?.any {
            it.asJsonObject.has("package_name")
        } == true })
        assertFalse(rules.any { it.asJsonObject.keySet() == setOf("outbound") })
    }

    @Test
    fun smartOffRetainsOriginalBehaviorRegardlessOfInstalledPolicy() {
        val root = config(custom(), smart = false)
        val json = root.toString()
        assertFalse(json.contains("overseas.example"))
        assertFalse(json.contains("mainland.example"))
        assertFalse(json.contains("com.example.international"))
        assertFalse(json.contains("192.0.2.12/32"))
        assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
        assertEquals("node.example.com", root.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject.getAsJsonArray("domain")[0].asString)
    }

    @Test
    fun systemRootAndHevShareExternalPolicyDnsAndBusinessRoutes() {
        val stable = config(custom())
        val route = stable.getAsJsonObject("route").getAsJsonArray("rules")
        for (adapted in listOf(RootConfigAdapter.adapt(stable.toString()).configJson,
            HevConfigAdapter.adapt(stable.toString()).configJson)) {
            val runtime = JsonParser.parseString(adapted).asJsonObject
            assertEquals(stable.getAsJsonObject("dns"), runtime.getAsJsonObject("dns"))
            assertEquals(stable.getAsJsonArray("outbounds"), runtime.getAsJsonArray("outbounds"))
            val runtimeRules = runtime.getAsJsonObject("route").getAsJsonArray("rules")
            assertEquals(route.toList(), runtimeRules.drop(1))
        }
    }
}

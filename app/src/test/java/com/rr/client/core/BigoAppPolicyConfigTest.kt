package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production compiler checks; native domain/IP matcher probes live in verify-routing.py. */
class BigoAppPolicyConfigTest {
    private val exactHosts = listOf(
        "bglvlbs.piojm.tech", "conf-lv.piojm.tech", "support0.dfaklj.tech"
    )
    private val suffixes = listOf("bigo.sg", "bigo.tv")
    private val node = ProxyNode(
        id = "bigo-test", tag = "bigo-test", type = ProtocolType.SOCKS,
        server = "192.0.2.1", serverPort = 1080,
        rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}"""
    )

    private fun config(smart: Boolean = true, bundled: Boolean = false): JsonObject =
        JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
            selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(),
            smartRouting = smart,
            ruleSets = if (bundled) ChinaRuleSetManager.Paths("/rules/cn.srs", "/rules/ip.srs") else null
        )).asJsonObject

    private fun rules(root: JsonObject, section: String = "route"): List<JsonObject> =
        root.getAsJsonObject(section).getAsJsonArray("rules").map { it.asJsonObject }

    private fun bigoGuard(root: JsonObject): JsonObject = rules(root).single { rule ->
        rule.getAsJsonArray("rules")?.any { child ->
            child.asJsonObject.getAsJsonArray("package_name")?.any {
                it.asString == "sg.bigo.live"
            } == true
        } == true
    }

    private fun domainRule(root: JsonObject, section: String): JsonObject = rules(root, section).single {
        it.getAsJsonArray("domain")?.any { domain -> domain.asString == exactHosts.first() } == true
    }

    private fun predicate(rule: JsonObject) = rule.deepCopy().apply {
        remove("outbound"); remove("server"); remove("action")
    }

    // Only checks explicit domain coverage, never simulates package, IP or rule-set matching.
    private fun matchesDomain(rule: JsonObject, host: String): Boolean =
        rule.getAsJsonArray("domain")?.any { it.asString == host } == true ||
            rule.getAsJsonArray("domain_suffix")?.any {
                host == it.asString || host.endsWith("." + it.asString)
            } == true

    @Test
    fun exactBigoGuardPrecedesDomesticDomainsAndIpRulesButFollowsDnsHijack() {
        for (bundled in listOf(false, true)) {
            val root = config(bundled = bundled)
            val route = rules(root)
            val guard = bigoGuard(root)
            val children = guard.getAsJsonArray("rules").map { it.asJsonObject }
            assertEquals(setOf("type", "mode", "rules", "action", "outbound"), guard.keySet())
            assertEquals("logical", guard.get("type").asString)
            assertEquals("and", guard.get("mode").asString)
            assertEquals("route", guard.get("action").asString)
            assertEquals("proxy", guard.get("outbound").asString)
            assertEquals(2, children.size)
            assertEquals(setOf("package_name"), children[0].keySet())
            assertEquals(listOf("sg.bigo.live"), children[0].getAsJsonArray("package_name").map { it.asString })
            assertEquals(JsonParser.parseString("""{"ip_is_private":true,"invert":true}"""), children[1])
            val guardIndex = route.indexOf(guard)
            val dnsIndex = route.indexOfFirst { it.get("action")?.asString == "hijack-dns" }
            assertTrue(dnsIndex >= 0 && guardIndex > dnsIndex)
            val businessIndices = route.indices.filter {
                route[it].has("domain") || route[it].has("domain_suffix") ||
                    route[it].has("ip_cidr") || route[it].has("ip_is_private") || route[it].has("rule_set")
            }
            assertTrue(businessIndices.isNotEmpty() && businessIndices.all { guardIndex < it })
            assertTrue(route.any {
                it.get("ip_is_private")?.asBoolean == true && it.get("outbound")?.asString == "direct"
            })
        }
    }

    @Test
    fun bigoDomainAndDnsPoliciesUseIdenticalNarrowPredicatesAheadOfChinaFallbacks() {
        for (bundled in listOf(false, true)) {
            val root = config(bundled = bundled)
            val route = domainRule(root, "route")
            val dns = domainRule(root, "dns")
            assertEquals(exactHosts, route.getAsJsonArray("domain").map { it.asString })
            assertEquals(suffixes, route.getAsJsonArray("domain_suffix").map { it.asString })
            assertEquals("proxy", route.get("outbound").asString)
            assertEquals("dns-remote", dns.get("server").asString)
            assertEquals(predicate(route), predicate(dns))
            for (section in listOf("route", "dns")) {
                val allRules = rules(root, section)
                val policyIndex = allRules.indexOf(domainRule(root, section))
                val key = if (section == "route") "outbound" else "server"
                val direct = if (section == "route") "direct" else "dns-direct"
                val fallbackIndices = allRules.indices.filter {
                    allRules[it].get(key)?.asString == direct || allRules[it].has("rule_set")
                }
                assertTrue(fallbackIndices.isNotEmpty() && fallbackIndices.all { policyIndex < it })
                for (host in exactHosts + suffixes + listOf("api.bigo.sg", "www.bigo.tv")) {
                    assertEquals("$section for $host", domainRule(root, section),
                        allRules.first { matchesDomain(it, host) })
                }
            }
        }
    }

    @Test
    fun observedHostRootsCloudTenantsAndLookalikesDoNotAcquireBigoExceptions() {
        val root = config(bundled = true)
        val untouched = listOf(
            "piojm.tech", "dfaklj.tech", "other.piojm.tech", "other.dfaklj.tech",
            "sub.bglvlbs.piojm.tech", "sub.conf-lv.piojm.tech", "sub.support0.dfaklj.tech",
            "bglvlbs.piojm.tech.evil.invalid", "conf-lv.piojm.tech.evil.invalid",
            "support0.dfaklj.tech.evil.invalid", "evilbigo.sg", "evilbigo.tv",
            "bigo.sg.evil.invalid", "bigo.tv.evil.invalid",
            "myqcloud.com", "customer.myqcloud.com", "aliyuncs.com", "customer.aliyuncs.com",
            "amazonaws.com", "customer.amazonaws.com", "cloudfront.net", "customer.cloudfront.net"
        )
        for (section in listOf("route", "dns")) {
            for (host in untouched) {
                assertFalse("$section unexpectedly gained an explicit domain exception for $host",
                    rules(root, section).any { matchesDomain(it, host) })
            }
        }
        val packages = bigoGuard(root).getAsJsonArray("rules")[0].asJsonObject
            .getAsJsonArray("package_name").map { it.asString }
        for (name in listOf("sg.bigo", "sg.bigo.live.lite", "sg.bigo.live.plugin", "com.bigo.live")) {
            assertFalse("Neighboring package captured: $name", packages.contains(name))
        }
        // Domain/app exceptions must not grow the existing public-IP bypass list.
        val ipRules = rules(root).filter { it.has("ip_cidr") }
        assertEquals(1, ipRules.size)
        assertEquals(listOf("8.134.241.67/32", "8.134.241.7/32", "8.134.240.5/32"),
            ipRules.single().getAsJsonArray("ip_cidr").map { it.asString })
        assertEquals("direct", ipRules.single().get("outbound").asString)
    }

    @Test
    fun rootAndHevPreserveCanonicalPolicyWhileUsingTheirOwnDnsIngress() {
        for (bundled in listOf(false, true)) {
            val stable = config(bundled = bundled)
            val root = JsonParser.parseString(RootConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
            val hev = JsonParser.parseString(HevConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
            for ((adapted, inboundTag) in listOf(root to "tun-in", hev to "hev-socks-in")) {
                val businessRoute = adapted.getAsJsonObject("route").deepCopy()
                val ingress = businessRoute.getAsJsonArray("rules").remove(0).asJsonObject
                assertEquals("hijack-dns", ingress.get("action").asString)
                assertEquals(listOf(inboundTag), ingress.getAsJsonArray("inbound").map { it.asString })
                assertEquals(53, ingress.get("port").asInt)
                assertEquals(listOf("tcp", "udp"), ingress.getAsJsonArray("network").map { it.asString })
                assertEquals(stable.get("route"), businessRoute)
                assertEquals(stable.get("dns"), adapted.get("dns"))
                assertEquals(stable.get("outbounds"), adapted.get("outbounds"))
                assertEquals(bigoGuard(stable), bigoGuard(adapted))
                assertEquals(domainRule(stable, "route"), domainRule(adapted, "route"))
            }
            assertFalse(rules(root).first().has("ip_cidr"))
            assertEquals(listOf("198.18.0.2/32"), rules(hev).first().getAsJsonArray("ip_cidr").map { it.asString })
            // Retaining the rule is configuration parity, not proof of original UID over SOCKS.
            val hevInbound = hev.getAsJsonArray("inbounds").single().asJsonObject
            assertEquals("socks", hevInbound.get("type").asString)
            assertFalse(hevInbound.has("package_name"))
            assertFalse(hevInbound.has("include_package"))
            assertFalse(hevInbound.has("exclude_package"))
        }
    }

    @Test
    fun smartOffRemovesBigoRulesInSystemRootAndHevConfigurations() {
        val stable = config(smart = false, bundled = true)
        val root = JsonParser.parseString(RootConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
        val hev = JsonParser.parseString(HevConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
        for (config in listOf(stable, root, hev)) {
            for (section in listOf("route", "dns")) {
                assertTrue(rules(config, section).none {
                    it.has("package_name") || it.has("type") || it.has("domain") || it.has("domain_suffix")
                })
            }
            assertEquals("proxy", config.getAsJsonObject("route").get("final").asString)
            assertEquals("dns-remote", config.getAsJsonObject("dns").get("final").asString)
            assertFalse(config.getAsJsonObject("route").has("rule_set"))
        }
    }
}

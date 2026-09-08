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

/** Checks the production compiler; native HTTP/TLS/TCP/UDP probes run in verify-routing.py. */
class ServiceEndpointRoutingConfigTest {
    private val endpoints = listOf(
        "gskd.sdoprofile.com", "pics.sdoprofile.com", "vpp-license-proxy.aliyuncs.com"
    )
    private val observedIps = listOf("8.134.241.67/32", "8.134.241.7/32", "8.134.240.5/32")
    private val node = ProxyNode(
        id = "endpoint-test", tag = "endpoint-test", type = ProtocolType.SOCKS,
        server = "192.0.2.1", serverPort = 1080,
        rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}"""
    )

    private fun config(smart: Boolean = true, bundled: Boolean = false, hev: Boolean = false): JsonObject {
        val config = ConfigBuilder.buildSingBoxConfig(
            selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(),
            smartRouting = smart,
            ruleSets = if (bundled) ChinaRuleSetManager.Paths("/rules/cn.srs", "/rules/ip.srs") else null
        )
        return JsonParser.parseString(if (hev) HevConfigAdapter.adapt(config).configJson else config).asJsonObject
    }

    private fun rules(root: JsonObject, section: String): List<JsonObject> =
        root.getAsJsonObject(section).getAsJsonArray("rules").map { it.asJsonObject }

    private fun predicate(rule: JsonObject) = rule.deepCopy().apply {
        remove("outbound"); remove("server"); remove("action")
    }

    @Test
    fun verifiedEndpointsUseIdenticalExactRouteAndDnsRulesInBothEngines() {
        for (bundled in listOf(false, true)) for (hev in listOf(false, true)) {
            val root = config(bundled = bundled, hev = hev)
            val route = rules(root, "route").single {
                it.getAsJsonArray("domain")?.any { d -> d.asString == endpoints.first() } == true
            }
            val dns = rules(root, "dns").single {
                it.getAsJsonArray("domain")?.any { d -> d.asString == endpoints.first() } == true
            }
            assertEquals(endpoints, route.getAsJsonArray("domain").map { it.asString })
            assertEquals("direct", route.get("outbound").asString)
            assertEquals("dns-direct", dns.get("server").asString)
            assertEquals(predicate(route), predicate(dns))
            assertFalse(route.has("domain_suffix"))
        }
    }

    @Test
    fun sharedCloudAndUnverifiedSdkDomainsDoNotAcquireBusinessExceptions() {
        val root = config()
        val untouched = listOf(
            "sdoprofile.com", "other.sdoprofile.com", "sub.pics.sdoprofile.com",
            "pics.sdoprofile.com.evil.invalid", "gskd.sdoprofile.com.evil.invalid",
            "aliyuncs.com", "customer.aliyuncs.com", "sub.vpp-license-proxy.aliyuncs.com",
            "vpp-license-proxy.aliyuncs.com.evil.invalid", "sysdk.cl2009.com",
            "cl2009.com", "time.google.com", "firebaselogging.googleapis.com"
        )
        for (section in listOf("route", "dns")) {
            val key = if (section == "route") "outbound" else "server"
            val direct = if (section == "route") "direct" else "dns-direct"
            val directRules = rules(root, section).filter { it.get(key)?.asString == direct }
            for (host in untouched) {
                assertFalse("$section unexpectedly bypassed $host", directRules.any { rule ->
                    rule.getAsJsonArray("domain")?.any { it.asString == host } == true ||
                        rule.getAsJsonArray("domain_suffix")?.any {
                            host == it.asString || host.endsWith("." + it.asString)
                        } == true
                })
            }
        }
    }

    @Test
    fun onlyThreeObservedHostsReceiveOfflineIpExceptionsAfterInternationalPolicies() {
        for (bundled in listOf(false, true)) for (hev in listOf(false, true)) {
            val rules = rules(config(bundled = bundled, hev = hev), "route")
            val exception = rules.single {
                it.getAsJsonArray("ip_cidr")?.any { ip -> ip.asString == observedIps.first() } == true
            }
            assertEquals(observedIps, exception.getAsJsonArray("ip_cidr").map { it.asString })
            assertEquals("direct", exception.get("outbound").asString)
            assertEquals(setOf("ip_cidr", "outbound"), exception.keySet())
            val exceptionIndex = rules.indexOf(exception)
            val appGuardIndex = rules.indexOfFirst { it.get("type")?.asString == "logical" }
            assertTrue(appGuardIndex >= 0 && appGuardIndex < exceptionIndex)
            val domainIndices = rules.indices.filter {
                rules[it].has("domain") || rules[it].has("domain_suffix")
            }
            assertTrue(domainIndices.isNotEmpty() && domainIndices.all { it < exceptionIndex })
            if (bundled) assertTrue(exceptionIndex < rules.indexOfFirst { it.has("rule_set") })
        }
    }

    @Test
    fun smartOffRemovesBothEndpointAndObservedIpSupplements() {
        for (hev in listOf(false, true)) {
            val root = config(smart = false, bundled = true, hev = hev)
            assertFalse(rules(root, "route").any { it.get("outbound")?.asString == "direct" })
            assertFalse(rules(root, "route").any {
                it.getAsJsonArray("ip_cidr")?.any { ip -> ip.asString in observedIps } == true
            })
            assertFalse(rules(root, "dns").any {
                it.getAsJsonArray("domain")?.any { d -> d.asString in endpoints } == true
            })
            assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
        }
    }
}

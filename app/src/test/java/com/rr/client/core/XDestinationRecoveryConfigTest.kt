package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.RoutingPolicySnapshot
import com.rr.client.routing.XDestinationRecoveryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native probes separately check actual old-IP recovery and UDP reverse translation. */
class XDestinationRecoveryConfigTest {
    private val node = ProxyNode(id = "x-recovery", tag = "x-recovery", type = ProtocolType.SOCKS,
        server = "node.example.com", serverPort = 1080,
        rawJson = """{"type":"socks","server":"node.example.com","server_port":1080,"version":"5"}""")

    private fun policyJson(): JsonObject = JsonParser.parseString(listOf(
        File("src/main/assets/rules/rrbox-policy.json"), File("app/src/main/assets/rules/rrbox-policy.json")
    ).first { it.isFile }.readText()).asJsonObject

    private fun config(
        policy: RoutingPolicySnapshot = RoutingPolicySnapshot.parse(policyJson().toString()),
        smart: Boolean = true, selected: ProxyNode = node
    ): JsonObject = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
        selected, listOf(selected), emptyList(), smartRouting = smart, routingPolicy = policy
    )).asJsonObject

    private fun rules(root: JsonObject) = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
    private fun recoveryRules(root: JsonObject) = rules(root).filter {
        it["action"]?.asString in setOf("route-options", "resolve")
    }
    private fun domains(rule: JsonObject) = rule.getAsJsonArray("rules")[0].asJsonObject
        .getAsJsonArray("domain").map { it.asString }

    @Test
    fun staleAddressRecoveryIsExactProtocolBoundedAndAlwaysPrecedesPackageRoutes() {
        val root = config()
        val route = rules(root)
        val recovery = recoveryRules(root)
        val overrides = recovery.filter { it["action"].asString == "route-options" }
        val expectedHosts = XDestinationRecoveryPolicy.bundledRule().domains
        assertEquals(expectedHosts, overrides.map { it["override_address"].asString })
        assertEquals(expectedHosts.size + 1, recovery.size)
        for (rule in recovery) {
            assertEquals("logical", rule["type"].asString)
            assertEquals("and", rule["mode"].asString)
            val parts = rule.getAsJsonArray("rules").map { it.asJsonObject }
            assertEquals(listOf("http", "tls", "quic"), parts[1].getAsJsonArray("protocol").map { it.asString })
            assertEquals(listOf("tcp", "udp"), parts[2].getAsJsonArray("network").map { it.asString })
            assertEquals(JsonParser.parseString("""{"ip_is_private":true,"invert":true}"""), parts[3])
            assertTrue(parts[4]["invert"].asBoolean)
            assertEquals(XDestinationRecoveryPolicy.excludedDestinationCidrs,
                parts[4].getAsJsonArray("ip_cidr").map { it.asString })
            assertFalse(rule.has("outbound"))
            assertFalse(rule.has("override_port"))
            assertFalse(rule.has("udp_disable_domain_unmapping"))
        }
        overrides.forEach { assertEquals(listOf(it["override_address"].asString), domains(it)) }
        val resolve = recovery.last()
        assertEquals("resolve", resolve["action"].asString)
        assertEquals(expectedHosts, domains(resolve))
        assertEquals("dns-remote", resolve["server"].asString)
        assertEquals("prefer_ipv4", resolve["strategy"].asString)
        assertFalse(resolve.has("override_address"))
        assertFalse(resolve.has("disable_cache"))
        val packageIndex = route.indexOfFirst { it["outbound"]?.asString == "proxy" && it.has("rules") }
        assertTrue(packageIndex > route.indexOf(resolve))
        assertTrue(route.indexOf(recovery.first()) > route.indexOfFirst { it["action"]?.asString == "sniff" })
        assertTrue(route.indexOf(recovery.first()) > route.indexOfFirst { it["action"]?.asString == "hijack-dns" })
    }

    @Test
    fun allThreeEnginesUseTheSameRecoveryAndExistingOutbounds() {
        val stable = config()
        for (text in listOf(RootConfigAdapter.adapt(stable.toString()).configJson,
            HevConfigAdapter.adapt(stable.toString()).configJson)) {
            val adapted = JsonParser.parseString(text).asJsonObject
            assertEquals(recoveryRules(stable), recoveryRules(adapted))
            assertEquals(stable["dns"], adapted["dns"])
            assertEquals(stable["outbounds"], adapted["outbounds"])
        }
        val remote = stable.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }
            .single { it["tag"].asString == "dns-remote" }
        assertEquals("tls", remote["type"].asString)
        assertEquals("proxy", remote["detour"].asString)
    }

    @Test
    fun syntheticPrivateAndSpecialPoolsCannotBeRecoveredToPublicServiceNames() {
        val protected = XDestinationRecoveryPolicy.excludedDestinationCidrs
        for (range in listOf("100.64.0.0/10", "198.18.0.0/15", "10.0.0.0/8", "172.16.0.0/12",
            "127.0.0.0/8", "169.254.0.0/16", "224.0.0.0/4", "::1/128", "fc00::/7", "fe80::/10", "ff00::/8")) {
            assertTrue("Missing original-destination protection for $range", range in protected)
        }
        assertFalse("Normal public destinations must remain eligible", "0.0.0.0/0" in protected)
        assertFalse("Normal IPv6 must remain eligible", "::/0" in protected)
    }

    @Test
    fun bootstrapEndpointIsExcludedFromBothRewriteAndResolve() {
        val bootstrap = node.copy(server = "api.twitter.com",
            rawJson = """{"type":"socks","server":"api.twitter.com","server_port":1080,"version":"5"}""")
        val root = config(selected = bootstrap)
        recoveryRules(root).forEach { assertFalse("api.twitter.com" in domains(it)) }
        val dns = root.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject
        assertEquals(listOf("api.twitter.com"), dns.getAsJsonArray("domain").map { it.asString })
        assertEquals("dns-direct", dns["server"].asString)
        val imported = node.copy(rawJson = """{"type":"socks","server":"api.twitter.com","server_port":1080,"version":"5"}""")
        recoveryRules(config(selected = imported)).forEach { assertFalse("api.twitter.com" in domains(it)) }
    }

    @Test
    fun oldSchemaOnePolicyWithoutTheReservedGroupKeepsItsOriginalRoutingBehavior() {
        val data = policyJson()
        data.addProperty("ruleVersion", 2026090801)
        data.add("domainRules", JsonArray().apply {
            data.getAsJsonArray("domainRules").filter {
                it.asJsonObject["id"].asString != XDestinationRecoveryPolicy.RULE_ID
            }.forEach(::add)
        })
        val root = config(RoutingPolicySnapshot.parse(data.toString()))
        assertTrue(recoveryRules(root).isEmpty())
        assertTrue(rules(root).any { it.getAsJsonArray("domain_suffix")?.any { domain -> domain.asString == "twitter.com" } == true })
    }

    @Test
    fun downloadedExactHostEditsAndRemovalControlRecoveryWithoutASecondHostList() {
        val data = policyJson()
        val group = data.getAsJsonArray("domainRules").single {
            it.asJsonObject["id"].asString == XDestinationRecoveryPolicy.RULE_ID
        }.asJsonObject
        group.add("domains", JsonArray().apply { add("api.twitter.com"); add("new-api.x.com") })
        data.addProperty("ruleVersion", 2026090803)
        val root = config(RoutingPolicySnapshot.parse(data.toString()))
        val overrides = recoveryRules(root).filter { it.has("override_address") }
        assertEquals(listOf("api.twitter.com", "new-api.x.com"), overrides.map { it["override_address"].asString })
        assertFalse(overrides.any { it["override_address"].asString == "video.twimg.com" })
    }

    @Test
    fun unobservedHostsEchCoverNamesAndOtherServicesHaveNoAddressOverride() {
        val targets = recoveryRules(config()).filter { it.has("override_address") }.map { it["override_address"].asString }
        for (host in listOf("unknown.twitter.com", "sub.api.twitter.com", "api.twitter.com.evil.example",
            "cloudflare-ech.com", "cloudflare.com", "chatgpt.com", "i.instagram.com", "gateway.kugou.com")) {
            assertFalse("Unexpected address recovery for $host", host in targets)
        }
    }

    @Test
    fun smartOffNeverAddsResolveOrAddressOverrideToAnyEngine() {
        val stable = config(smart = false)
        for (text in listOf(stable.toString(), RootConfigAdapter.adapt(stable.toString()).configJson,
            HevConfigAdapter.adapt(stable.toString()).configJson)) {
            assertTrue(recoveryRules(JsonParser.parseString(text).asJsonObject).isEmpty())
        }
    }
}

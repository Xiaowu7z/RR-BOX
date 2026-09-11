package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.AppNodeRouting
import com.rr.client.routing.JarvisAppPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNodeRoutingConfigTest {
    private val telegram = "org.telegram.messenger"
    private val chrome = "com.android.chrome"
    private val main = node("la", "la.example.com")
    private val hk = node("hk", "hk.example.com")

    private fun node(id: String, server: String) = ProxyNode(
        id = id, tag = id, type = ProtocolType.SOCKS, server = server, serverPort = 1080,
        rawJson = """{"type":"socks","server":"$server","server_port":1080,"version":"5"}"""
    )

    private fun config(
        bindings: List<AppNodeBinding> = listOf(AppNodeBinding(telegram, hk.id)),
        nodes: List<ProxyNode> = listOf(main, hk),
        smart: Boolean = false,
        selected: Set<String> = setOf(telegram, chrome),
        fast: Boolean = false
    ) = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
        selectedNode = main, allNodes = nodes, appRoutes = emptyList(), smartRouting = smart,
        perAppMode = "ALLOW_LIST", selectedPackages = selected, appNodeBindings = bindings,
        fastForwarding = fast
    )).asJsonObject

    private fun rules(root: JsonObject) = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
    private fun packages(rule: JsonObject) = rule.getAsJsonArray("package_name")?.map { it.asString }.orEmpty()
    private fun outlet(root: JsonObject, tag: String) = root.getAsJsonArray("outbounds")
        .single { it.asJsonObject.get("tag").asString == tag }.asJsonObject

    @Test
    fun smartOffKeepsMainAndCaptureWhileTelegramUsesExtraOutlet() {
        val root = config()
        assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
        assertEquals("la.example.com", outlet(root, "proxy").get("server").asString)
        assertEquals("hk.example.com", outlet(root, AppNodeRouting.nodeTag(hk.id)).get("server").asString)
        val rule = rules(root).single { telegram in packages(it) }
        assertEquals(AppNodeRouting.nodeTag(hk.id), rule.get("outbound").asString)
        assertEquals(setOf(telegram, chrome, "com.rr.client"), root.getAsJsonArray("inbounds")[0]
            .asJsonObject.getAsJsonArray("include_package").map { it.asString }.toSet())
        assertEquals(setOf("la", "hk"), AppNodeRouting.requiredNodeIds(root.toString(), "la"))
    }

    @Test
    fun sameNodeForMultipleAppsCreatesOnlyOneExtraOutletAndOneResolver() {
        val root = config(listOf(AppNodeBinding(telegram, "hk"), AppNodeBinding(chrome, "hk")))
        assertEquals(3, root.getAsJsonArray("outbounds").size())
        assertEquals(3, root.getAsJsonObject("dns").getAsJsonArray("servers").size())
        assertEquals(2, rules(root).count { it.get("outbound")?.asString == AppNodeRouting.nodeTag("hk") })
    }

    @Test
    fun explicitMainBindingReusesMainAndOverridesSmartDirectPolicy() {
        val root = config(listOf(AppNodeBinding(JarvisAppPolicy.PACKAGE_NAME, main.id)), smart = true,
            selected = setOf(JarvisAppPolicy.PACKAGE_NAME))
        assertEquals(2, root.getAsJsonArray("outbounds").size())
        val first = rules(root).first { JarvisAppPolicy.PACKAGE_NAME in packages(it) }
        assertEquals("proxy", first.get("outbound").asString)
    }

    @Test
    fun bindingsPrecedeSmartAppAndDomainPoliciesButFollowDnsHijack() {
        val root = config(listOf(AppNodeBinding(JarvisAppPolicy.PACKAGE_NAME, hk.id)), smart = true,
            selected = setOf(JarvisAppPolicy.PACKAGE_NAME))
        val routeRules = rules(root)
        val bindingIndex = routeRules.indexOfFirst { it.get("outbound")?.asString == AppNodeRouting.nodeTag("hk") }
        assertTrue(bindingIndex > routeRules.indexOfFirst { it.get("action")?.asString == "hijack-dns" })
        assertTrue(bindingIndex < routeRules.indexOfFirst { it.get("outbound")?.asString == "direct" })
        assertTrue(bindingIndex < routeRules.indexOfFirst { it.has("domain_suffix") })
    }

    @Test
    fun missingSecondaryRejectsOnlyItsAppAndKeepsMainRunning() {
        for (smart in listOf(false, true)) {
            val root = config(nodes = listOf(main), smart = smart)
            assertEquals(2, root.getAsJsonArray("outbounds").size())
            val reject = rules(root).first { telegram in packages(it) }
            assertEquals("reject", reject.get("action").asString)
            assertFalse(reject.has("outbound"))
            val dnsReject = root.getAsJsonObject("dns").getAsJsonArray("rules")
                .map { it.asJsonObject }.first { telegram in packages(it) }
            assertEquals("reject", dnsReject.get("action").asString)
            assertEquals(setOf("la"), AppNodeRouting.requiredNodeIds(root.toString(), "la"))
            assertEquals("proxy", root.getAsJsonObject("route").get("final").asString)
        }
    }

    @Test
    fun invalidSecondaryParametersCannotPoisonMainConfiguration() {
        val invalidNodes = listOf(
            hk.copy(type = ProtocolType.VLESS_TLS, rawJson = "", uuidOrPassword = ""),
            hk.copy(rawJson = """{"type":"socks","server":"","server_port":1080}"""),
            hk.copy(rawJson = """{"type":"socks","server":"hk.example.com","server_port":90000}"""),
            hk.copy(rawJson = "not-json")
        )
        invalidNodes.forEach { invalid ->
            val root = config(nodes = listOf(main, invalid))
            assertEquals("reject", rules(root).first { telegram in packages(it) }.get("action").asString)
            assertEquals(2, root.getAsJsonArray("outbounds").size())
        }
    }

    @Test
    fun disabledOrUncapturedBindingPreservesOldConfigurationExactly() {
        val baseline = config(bindings = emptyList())
        assertEquals(baseline, config(bindings = listOf(AppNodeBinding(telegram, "hk", false))))
        assertEquals(baseline, config(bindings = listOf(AppNodeBinding("com.example.unselected", "hk"))))
    }

    @Test
    fun appDnsUsesItsOutletWhileSharedNetdRequestsRetainMainResolver() {
        val root = config(smart = true)
        val dns = root.getAsJsonObject("dns")
        val rule = dns.getAsJsonArray("rules").map { it.asJsonObject }.first { telegram in packages(it) }
        val server = dns.getAsJsonArray("servers").map { it.asJsonObject }
            .single { it.get("tag").asString == rule.get("server").asString }
        assertEquals(AppNodeRouting.nodeTag("hk"), server.get("detour").asString)
        assertEquals("tls", server.get("type").asString)
        assertEquals("cloudflare-dns.com", server.getAsJsonObject("tls").get("server_name").asString)
        assertEquals("dns-remote", dns.get("final").asString)
    }

    @Test
    fun eachRawAndDisplayBootstrapHostIsExcludedFromRecoveryAndResolvedDirectly() {
        val displayHost = "api.x.com"
        val rawHost = "szextshort.weixin.qq.com"
        val extra = node("hk", rawHost).copy(server = displayHost)
        val root = config(nodes = listOf(main, extra), smart = true)
        assertEquals("dns-direct", outlet(root, AppNodeRouting.nodeTag("hk")).get("domain_resolver").asString)
        assertFalse(rules(root).any { it.get("override_address")?.asString in setOf(displayHost, rawHost) })
        val dnsRules = root.getAsJsonObject("dns").getAsJsonArray("rules").map { it.asJsonObject }
        for (host in listOf(displayHost, rawHost)) {
            val hostRule = dnsRules.first { it.getAsJsonArray("domain")?.any { item -> item.asString == host } == true }
            assertEquals("dns-direct", hostRule.get("server").asString)
            assertTrue(dnsRules.indexOf(hostRule) < dnsRules.indexOfFirst { telegram in packages(it) })
        }
    }

    @Test
    fun activeBindingsGuardUnknownTcpAndUdpOwnerEvenInFastMode() {
        val root = config(fast = true)
        val guard = rules(root).single { rule -> rule.getAsJsonArray("rules")?.any {
            it.asJsonObject.has("package_name_regex")
        } == true }
        assertEquals("reject", guard.get("action").asString)
        val conditions = guard.getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf("tcp", "udp"), conditions.first().getAsJsonArray("network").map { it.asString })
        assertTrue(conditions.last().get("invert").asBoolean)
        assertEquals(".+", conditions.last().getAsJsonArray("package_name_regex")[0].asString)
        assertTrue(rules(root).indexOf(guard) > rules(root).indexOfFirst { it.get("action")?.asString == "hijack-dns" })
    }
}

package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.RoutingPolicySnapshot
import com.rr.client.routing.WeChatIpv6RecoveryPolicy
import com.rr.client.routing.XDestinationRecoveryPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native direct-dialer probes separately validate TCP family fallback and unchanged UDP. */
class WeChatIpv6RecoveryConfigTest {
    private val node = ProxyNode(id = "wechat-recovery", tag = "wechat-recovery", type = ProtocolType.SOCKS,
        server = "node.example.com", serverPort = 1080,
        rawJson = """{"type":"socks","server":"node.example.com","server_port":1080,"version":"5"}""")

    private val observedHosts = listOf(
        "szextshort.weixin.qq.com", "wx.qlogo.cn", "szshort.weixin.qq.com",
        "dns.weixin.qq.com.cn", "szminorshort.weixin.qq.com", "dldir1.qq.com",
        "c2c.cdn.weixin.qq.com", "snsqpic.cdn.weixin.qq.com", "szshort.pay.weixin.qq.com",
        "szlong.weixin.qq.com", "paydns.wechatpay.cn", "szshort.mixpay.wechatpay.cn",
        "sni.cdn.weixin.qq.com"
    )

    private fun policyJson() = JsonParser.parseString(listOf(
        File("src/main/assets/rules/rrbox-policy.json"), File("app/src/main/assets/rules/rrbox-policy.json")
    ).first { it.isFile }.readText()).asJsonObject

    private fun config(
        policy: RoutingPolicySnapshot = RoutingPolicySnapshot.parse(policyJson().toString()),
        smart: Boolean = true, selected: ProxyNode = node, fast: Boolean = false
    ) = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(selected, listOf(selected), emptyList(),
        smartRouting = smart, fastForwarding = fast, routingPolicy = policy)).asJsonObject

    private fun rules(root: JsonObject) = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
    private fun recovery(root: JsonObject) = rules(root).filter {
        it["action"]?.asString == "route" && it["outbound"]?.asString == "direct" && it.has("override_address")
    }
    private fun targets(root: JsonObject) = recovery(root).map { it["override_address"].asString }
    private fun parts(rule: JsonObject) = rule.getAsJsonArray("rules").map { it.asJsonObject }

    private fun prepend(data: JsonObject, vararg additions: String): RoutingPolicySnapshot {
        val existing = data.getAsJsonArray("domainRules")
        data.add("domainRules", JsonArray().apply {
            additions.forEach { add(JsonParser.parseString(it)) }
            existing.forEach(::add)
        })
        return RoutingPolicySnapshot.parse(data.toString())
    }

    private fun onlyRules(vararg entries: String): RoutingPolicySnapshot = RoutingPolicySnapshot.parse(
        policyJson().apply {
            add("domainRules", JsonArray().apply { entries.forEach { add(JsonParser.parseString(it)) } })
        }.toString()
    )

    @Test
    fun onlyReviewedPublicIpv6HostsRecoverAfterProxyPoliciesBeforeDirectDomains() {
        val root = config()
        val all = rules(root)
        val recovery = recovery(root)
        assertEquals(observedHosts, targets(root))
        val packages = all.filter { it["outbound"]?.asString == "proxy" && it.has("rules") }
        assertTrue(packages.isNotEmpty())
        assertTrue(packages.all { all.indexOf(it) < all.indexOf(recovery.first()) })
        val proxyDomains = all.filter {
            it["outbound"]?.asString == "proxy" && (it.has("domain") || it.has("domain_suffix"))
        }
        assertTrue(proxyDomains.isNotEmpty())
        assertTrue(proxyDomains.all { all.indexOf(it) < all.indexOf(recovery.first()) })
        assertTrue(all.indexOf(recovery.last()) < all.indexOfFirst {
            it["outbound"]?.asString == "direct" && (it.has("domain") || it.has("domain_suffix"))
        })
        for (rule in recovery) {
            assertEquals("and", rule["mode"].asString)
            val match = parts(rule)
            assertEquals(listOf(rule["override_address"].asString), match[0].getAsJsonArray("domain").map { it.asString })
            assertFalse(match[0].has("domain_suffix"))
            assertEquals(6, match[1]["ip_version"].asInt)
            assertTrue(match[2]["ip_is_private"].asBoolean)
            assertTrue(match[2]["invert"].asBoolean)
            assertEquals(WeChatIpv6RecoveryPolicy.excludedDestinationCidrs,
                match[3].getAsJsonArray("ip_cidr").map { it.asString })
            assertTrue(match[3]["invert"].asBoolean)
            assertFalse(rule.has("override_port"))
            assertFalse(rule.has("udp_disable_domain_unmapping"))
            assertFalse(rule.has("port"))
        }
    }

    @Test
    fun recoveryIsTcpOnlyAndOpaquePayloadsRequireVerifiedWechatOwner() {
        val rule = recovery(config()).first()
        val match = parts(rule)
        assertEquals("tcp", match[4]["network"].asString)
        assertEquals("or", match[5]["mode"].asString)
        val identity = parts(match[5])
        assertEquals(listOf("http", "tls"), identity[0].getAsJsonArray("protocol").map { it.asString })
        assertEquals(listOf("com.tencent.mm"), identity[1].getAsJsonArray("package_name").map { it.asString })
        assertFalse(rule.toString().contains("quic"))
        assertFalse(rule.toString().contains("udp"))
    }

    @Test
    fun directUsesExplicitIpv4PreferenceWhileRetainingBothAddressFamilies() {
        val root = config()
        val direct = root.getAsJsonArray("outbounds").map { it.asJsonObject }.single { it["tag"].asString == "direct" }
        assertEquals(JsonParser.parseString("""{"server":"dns-direct","strategy":"prefer_ipv4"}"""), direct["domain_resolver"])
        assertFalse(root.toString().contains("ipv4_only"))
        val remote = root.getAsJsonObject("dns").getAsJsonArray("servers").map { it.asJsonObject }
            .single { it["tag"].asString == "dns-remote" }
        assertEquals("proxy", remote["detour"].asString)
        assertEquals("proxy", root.getAsJsonObject("route")["final"].asString)
    }

    @Test
    fun allEngineTemplatesAndFastModeShareRecoveryButSmartOffAddsNone() {
        val stable = config()
        for (text in listOf(stable.toString(), RootConfigAdapter.adapt(stable.toString()).configJson,
            HevConfigAdapter.adapt(stable.toString()).configJson, config(fast = true).toString())) {
            val runtime = JsonParser.parseString(text).asJsonObject
            assertEquals(recovery(stable), recovery(runtime))
            assertEquals(stable["outbounds"], runtime["outbounds"])
        }
        val root = JsonParser.parseString(RootConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
        assertTrue(root.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("address")
            .any { it.asString == RootConfigAdapter.TUN_IPV6_ADDRESS })
        val off = config(smart = false)
        for (text in listOf(off.toString(), RootConfigAdapter.adapt(off.toString()).configJson,
            HevConfigAdapter.adapt(off.toString()).configJson)) {
            assertTrue(recovery(JsonParser.parseString(text).asJsonObject).isEmpty())
        }
    }

    @Test
    fun currentPolicyFirstMatchWinsAndUnmatchedHostsReceiveNoRecovery() {
        val host = "szshort.weixin.qq.com"
        val proxy = """{"id":"custom-wechat-proxy","destination":"PROXY","suffixes":[],"domains":["$host"]}"""
        val direct = """{"id":"custom-wechat-direct","destination":"DIRECT","suffixes":["weixin.qq.com"],"domains":[]}"""
        assertFalse(host in targets(config(prepend(policyJson(), proxy))))
        val mixed = targets(config(onlyRules(proxy, direct)))
        assertFalse(host in mixed)
        assertTrue("szlong.weixin.qq.com" in mixed)
        assertTrue(host in targets(config(onlyRules(direct))))
        assertTrue(recovery(config(onlyRules(proxy))).isEmpty())
        // The downloaded policy schema itself disallows a domestic rule before PROXY.
        assertTrue(runCatching { onlyRules(direct, proxy) }.isFailure)
    }

    @Test
    fun displayAndImportedBootstrapNamesAreBothExcludedCaseInsensitively() {
        val selected = node.copy(server = "SZSHORT.WEIXIN.QQ.COM.",
            rawJson = """{"type":"socks","server":"SzLong.WeiXin.QQ.Com.","server_port":1080,"version":"5"}""")
        val actual = targets(config(selected = selected))
        assertFalse("szshort.weixin.qq.com" in actual)
        assertFalse("szlong.weixin.qq.com" in actual)
        assertEquals(11, actual.size)
    }

    @Test
    fun domainLookalikesUnobservedHostsAndSharedCdnRootsNeverBecomeReplacementTargets() {
        val broad = onlyRules(
            """{"id":"test-broad-direct","destination":"DIRECT","suffixes":["qq.com","cn","evil.invalid"],"domains":["cloudflare-ech.com"]}""")
        val actual = targets(config(broad))
        assertEquals(observedHosts, actual)
        for (host in listOf("szshort.weixin.qq.com.evil.invalid", "sub.szshort.weixin.qq.com",
            "unknown.weixin.qq.com", "weixin.qq.com", "qq.com", "cloudflare-ech.com",
            "hcdnd.txhy.cmcczjchongtu.v6.d.cdn.10086.cn")) {
            assertFalse(host in actual)
            assertFalse(WeChatIpv6RecoveryPolicy.isReviewedHost(host))
        }
        assertTrue(observedHosts.all(WeChatIpv6RecoveryPolicy::isReviewedHost))
    }

    @Test
    fun existingSchemaOnePoliciesWorkWithoutANewReservedGroupAndXRecoveryCoexists() {
        val data = policyJson()
        data.addProperty("ruleVersion", 2026090801)
        data.add("domainRules", JsonArray().apply {
            data.getAsJsonArray("domainRules").filter {
                it.asJsonObject["id"].asString != XDestinationRecoveryPolicy.RULE_ID
            }.forEach(::add)
        })
        val old = config(RoutingPolicySnapshot.parse(data.toString()))
        assertEquals(observedHosts, targets(old))
        assertFalse(rules(old).any { it["action"]?.asString == "route-options" })
        val current = config()
        val x = rules(current).filter { it["action"]?.asString == "route-options" }
        assertEquals(XDestinationRecoveryPolicy.bundledRule().domains, x.map { it["override_address"].asString })
        assertTrue(rules(current).indexOf(x.last()) < rules(current).indexOf(recovery(current).first()))
    }
}

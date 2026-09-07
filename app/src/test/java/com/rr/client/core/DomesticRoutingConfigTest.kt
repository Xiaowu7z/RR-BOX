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

/** Configuration compiler regression tests. Native matcher/UDP checks run separately in CI. */
class DomesticRoutingConfigTest {
    private val node = ProxyNode(
        id = "routing", tag = "routing", type = ProtocolType.SOCKS,
        server = "192.0.2.1", serverPort = 1080,
        rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}"""
    )

    private fun config(smart: Boolean = true, binaries: Boolean = false, server: String? = null): JsonObject {
        val selected = if (server == null) node else node.copy(
            server = server,
            rawJson = """{"type":"socks","server":"$server","server_port":1080,"version":"5"}"""
        )
        return JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
            selectedNode = selected,
            allNodes = listOf(selected),
            appRoutes = emptyList(),
            smartRouting = smart,
            ruleSets = if (binaries) ChinaRuleSetManager.Paths("/rules/cn.srs", "/rules/ip.srs") else null
        )).asJsonObject
    }

    // Only exercise emitted domain conditions. IP/rule-set conditions are deliberately not
    // simulated here. sing-box's DomainItem uses exact OR domain-boundary suffix matching.
    private fun domainDecision(config: JsonObject, section: String, host: String): String {
        val group = config.getAsJsonObject(section)
        val destination = if (section == "dns") "server" else "outbound"
        val domain = host.lowercase()
        return group.getAsJsonArray("rules").map { it.asJsonObject }.firstOrNull { rule ->
            rule.has(destination) && (
                rule.getAsJsonArray("domain")?.any { it.asString == domain } == true ||
                    rule.getAsJsonArray("domain_suffix")?.any {
                        domain == it.asString || domain.endsWith("." + it.asString)
                    } == true
                )
        }?.get(destination)?.asString ?: group.get("final").asString
    }

    private fun assertPolicy(root: JsonObject, hosts: List<String>, direct: Boolean) {
        hosts.forEach { host ->
            assertEquals("route for $host", if (direct) "direct" else "proxy", domainDecision(root, "route", host))
            assertEquals("DNS for $host", if (direct) "dns-direct" else "dns-remote", domainDecision(root, "dns", host))
        }
    }

    @Test
    fun wechatMessagesPaymentsChannelsAndShortLinksWorkWithoutDownloadedRules() {
        assertPolicy(config(), listOf(
            "long.weixin.qq.com", "short.weixin.qq.com", "mp.weixin.qq.com", "weixin110.qq.com",
            "api.mch.weixin.qq.com", "pay.weixin.qq.com", "wx.tenpay.com", "servicewechat.com",
            "mmbiz.qpic.cn", "wx.qlogo.cn", "finder.video.qq.com", "live.wx.wxlivecdn.com",
            "wxaurl.cn", "wxmpurl.cn", "mmbizurl.cn", "url.cn"
        ), direct = true)
    }

    @Test
    fun douyinCoreShortLinksPicturesVideoLiveAndCommerceWorkOffline() {
        assertPolicy(config(), listOf(
            "www.douyin.com", "v.douyin.com", "www.iesdouyin.com", "api.amemv.com",
            "p3.douyinpic.com", "v3.douyinvod.com", "v3.idouyinvod.com", "live.douyin.com",
            "pull.douyinliving.com", "lf3.douyinstatic.com", "open.douyin.com", "jinritemai.com",
            "cashier.douyinpay.com", "p3-dy.byteimg.com", "lf3-static.bytednsdoc.com"
        ), direct = true)
    }

    @Test
    fun patchedTikTokAndOtherInternationalServicesKeepProxyAndRemoteDns() {
        assertPolicy(config(binaries = true), listOf(
            "www.tiktok.com", "api16-normal-c-useast1a.tiktokv.com", "v16.tiktokcdn.com",
            "pull.ttlivecdn.com", "p16-sign.tiktokcdn-us.com", "api.tiktokv.eu", "api.tiktokv.us",
            "p16-sign-va.ibyteimg.com", "v16.byteoversea.com", "sf16.ibytedtos.com",
            "p16.muscdn.com", "p16-tiktokcdn-com.akamaized.net", "wetv.qq.com", "wetv.vip",
            "www.bilibili.tv", "overseas.weibo.com", "api.x.com", "video.twimg.com", "api.telegram.org",
            "rr1.googlevideo.com", "www.youtube.com", "chatgpt.com"
        ), direct = false)
    }

    @Test
    fun dailyShoppingFoodTravelPaymentsAndMediaHaveOfflineCoverage() {
        assertPolicy(config(), listOf(
            "api.alipay.com", "h5.m.taobao.com", "img.taobaocdn.com", "www.goofish.com",
            "api.m.jd.com", "img.360buyimg.com", "api.pinduoduo.com", "img.pddpic.com",
            "api.meituan.com", "img.meituan.net", "h5.ele.me", "restapi.amap.com",
            "map.baidu.com", "api.didichuxing.com", "www.ctrip.com", "kyfw.12306.cn",
            "www.95516.com", "api.bilibili.com", "video.bilivideo.com", "www.xiaohongshu.com",
            "img.xhscdn.com", "www.kuaishou.com", "www.zhihu.com", "pic.zhimg.com",
            "api.weibo.com", "wx1.sinaimg.cn", "music.163.com", "y.qq.com"
        ), direct = true)
    }

    @Test
    fun domainBoundariesAndExternalWebviewDestinationsArePreserved() {
        assertPolicy(config(), listOf(
            "evilqq.com", "notdouyin.com", "douyin.com.example.org", "weixin.qq.com.example.org",
            "qpic.cn.example.org", "wechat.com.example.org", "evilcn", "example.org",
            "arbitrary.byteimg.com", "arbitrary.snssdk.com", "customer.myqcloud.com",
            "customer.aliyuncs.com", "customer.cloudfront.net", "customer.akamaized.net"
        ), direct = false)
        // The compiled priority must select the overseas exception before qq.com or CN-IP rules.
        val root = config(binaries = true)
        val rules = root.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val overseas = rules.indexOfFirst { it.getAsJsonArray("domain")?.any { d -> d.asString == "wetv.qq.com" } == true }
        val domestic = rules.indexOfFirst { it.getAsJsonArray("domain_suffix")?.any { d -> d.asString == "qq.com" } == true }
        val china = rules.indexOfFirst { it.has("rule_set") }
        assertTrue(overseas >= 0 && overseas < domestic && domestic < china)
    }

    @Test
    fun dnsAndRouteCompileExactlyTheSameDomainPredicatesInTheSameOrder() {
        val root = config(binaries = true)
        fun predicates(section: String): List<JsonObject> = root.getAsJsonObject(section)
            .getAsJsonArray("rules").map { it.asJsonObject }.filter {
                it.has("domain") || it.has("domain_suffix")
            }.map { original -> original.deepCopy().apply {
                remove("outbound"); remove("server"); remove("action")
            } }
        assertEquals(predicates("route"), predicates("dns"))
        assertTrue(root.getAsJsonObject("dns").get("reverse_mapping").asBoolean)
        assertFalse(root.getAsJsonObject("dns").has("independent_cache"))
        val direct = root.getAsJsonArray("outbounds").map { it.asJsonObject }
            .first { it.get("tag").asString == "direct" }
        assertEquals("dns-direct", direct.get("domain_resolver").asString)
    }

    @Test
    fun proxyBootstrapIsExactAndDoesNotRedirectItsParentDomain() {
        val root = config(server = "edge.example.net")
        assertEquals("dns-direct", domainDecision(root, "dns", "edge.example.net"))
        assertEquals("dns-remote", domainDecision(root, "dns", "www.example.net"))
        assertEquals("proxy", domainDecision(root, "route", "edge.example.net"))
    }

    @Test
    fun smartOffRemovesAllBusinessPoliciesAndReverseMapping() {
        val root = config(smart = false, binaries = true)
        assertPolicy(root, listOf("long.weixin.qq.com", "v.douyin.com", "www.tiktok.com", "www.baidu.com"), direct = false)
        assertFalse(root.getAsJsonObject("dns").get("reverse_mapping").asBoolean)
        assertFalse(root.getAsJsonObject("route").has("rule_set"))
    }

    @Test
    fun hevKeepsKnownDomainAndDnsPoliciesAndDoesNotAddGlobalDnsOrUdpInterference() {
        val stable = config(binaries = true)
        val hev = JsonParser.parseString(HevConfigAdapter.adapt(stable.toString()).configJson).asJsonObject
        listOf("route", "dns", "outbounds").forEach { assertEquals(stable.get(it), hev.get(it)) }
        assertPolicy(hev, listOf("long.weixin.qq.com", "v.douyinvod.com"), direct = true)
        assertPolicy(hev, listOf("v.tiktokcdn.com", "p.ibyteimg.com"), direct = false)
        val rules = hev.getAsJsonObject("route").getAsJsonArray("rules")
        assertFalse(rules.any { it.asJsonObject.get("action")?.asString == "resolve" })
        assertFalse(rules.any { it.asJsonObject.get("action")?.asString == "reject" })
        assertFalse(rules.any { it.asJsonObject.has("package_name") || it.asJsonObject.has("network") })
        assertEquals("proxy", hev.getAsJsonObject("route").get("final").asString)
    }
}

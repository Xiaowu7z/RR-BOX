package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChatGptRoutingPolicy
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.RoutingPolicySnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Configuration parity and security boundaries; native TLS/HTTP probes run in CI. */
class ChatGptRoutingPolicyConfigTest {
    private val node = ProxyNode(id = "chatgpt-test", tag = "chatgpt-test", type = ProtocolType.SOCKS,
        server = "192.0.2.1", serverPort = 1080,
        rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}""")

    private fun snapshots(): List<RoutingPolicySnapshot> {
        val asset = listOf(File("src/main/assets/rules/rrbox-policy.json"),
            File("app/src/main/assets/rules/rrbox-policy.json")).first { it.isFile }
        return listOf(RoutingPolicySnapshot.bundled(), RoutingPolicySnapshot.parse(asset.readBytes()))
    }

    private fun config(policy: RoutingPolicySnapshot, smart: Boolean = true) = JsonParser.parseString(
        ConfigBuilder.buildSingBoxConfig(node, listOf(node), emptyList(), smartRouting = smart,
            ruleSets = ChinaRuleSetManager.Paths("/rules/cn.srs", "/rules/ip.srs"), routingPolicy = policy)
    ).asJsonObject

    private fun rules(root: JsonObject, section: String) = root.getAsJsonObject(section)
        .getAsJsonArray("rules").map { it.asJsonObject }

    private fun matches(rule: JsonObject, host: String) =
        rule.getAsJsonArray("domain")?.any { it.asString == host } == true ||
            rule.getAsJsonArray("domain_suffix")?.any { host == it.asString || host.endsWith("." + it.asString) } == true

    @Test
    fun officialDependenciesUseTheSameProxyAndRemoteDnsPriority() {
        // Independent examples include auxiliary providers missing from the old policy.
        val hosts = listOf("android.chat.openai.com", "ws.chatgpt.com", "setup.auth.openai.com",
            "files.oaiusercontent.com", "cdn.oaistatic.com", "feature.oaistatsig.com",
            "links.ct.sendgrid.net", "api.intercom.io", "widget.intercomcdn.com",
            "cdn.openaimerge.com", "cdn.workos.com", "challenges.cloudflare.com",
            "forwarder.workos.com", "humb.apple.com", "images.workoscdn.com", "js.stripe.com",
            "o207216.ingest.sentry.io", "o33249.ingest.sentry.io", "rum.browser-intake-datadoghq.com",
            "setup.workos.com", "workos.imgix.net")
        for (snapshot in snapshots()) {
            val root = config(snapshot)
            for ((section, target, key) in listOf(Triple("route", "proxy", "outbound"),
                Triple("dns", "dns-remote", "server"))) {
                val compiled = rules(root, section)
                for (host in hosts) {
                    val first = compiled.first { matches(it, host) }
                    assertEquals("$section: $host", target, first[key].asString)
                    assertTrue(compiled.indexOf(first) < compiled.indexOfFirst { it.has("rule_set") })
                }
            }
            val route = rules(root, "route").first { matches(it, "challenges.cloudflare.com") }
            val dns = rules(root, "dns").first { matches(it, "challenges.cloudflare.com") }
            assertEquals(route.deepCopy().apply { remove("outbound") },
                dns.deepCopy().apply { remove("action"); remove("server") })
            // The resolver remains encrypted through the chosen proxy, with certificate
            // validation enabled. No certificate workaround may weaken this policy.
            val remote = root.getAsJsonObject("dns").getAsJsonArray("servers")
                .single { it.asJsonObject["tag"].asString == "dns-remote" }.asJsonObject
            assertEquals("tls", remote["type"].asString)
            assertEquals("proxy", remote["detour"].asString)
            assertEquals("cloudflare-dns.com", remote.getAsJsonObject("tls")["server_name"].asString)
            assertFalse(remote.getAsJsonObject("tls")["insecure"]?.asBoolean ?: false)
        }
    }

    @Test
    fun sharedProvidersAndLookalikesDoNotBecomeChatGptExceptions() {
        val untouched = listOf("cloudflare.com", "customer.cloudflare.com", "sub.challenges.cloudflare.com",
            "workos.com", "customer.workos.com", "customer.workoscdn.com", "customer.stripe.com",
            "other.imgix.net", "o12345.ingest.sentry.io", "customer.sendgrid.net", "customer.apple.com",
            "customer.amazonaws.com", "customer.cloudfront.net", "evilopenai.com",
            "chatgpt.com.evil.invalid", "cdn.workos.com.evil.invalid")
        for (snapshot in snapshots()) {
            val root = config(snapshot)
            for (section in listOf("route", "dns")) {
                val rule = rules(root, section).single { matches(it, "challenges.cloudflare.com") }
                untouched.forEach { assertFalse("$section accidentally captures $it", matches(rule, it)) }
            }
        }
    }

    @Test
    fun exactAppIdentityProtectsOpaquePublicTrafficAndKeepsPrivateTrafficDirect() {
        for (snapshot in snapshots()) {
            val root = config(snapshot)
            val route = rules(root, "route")
            val guard = route.single { rule -> rule.getAsJsonArray("rules")?.any {
                it.asJsonObject.getAsJsonArray("package_name")?.any { p -> p.asString == ChatGptRoutingPolicy.PACKAGE_NAME } == true
            } == true }
            val conditions = guard.getAsJsonArray("rules").map { it.asJsonObject }
            assertEquals(listOf("com.openai.chatgpt"), conditions[0].getAsJsonArray("package_name").map { it.asString })
            assertEquals(JsonParser.parseString("""{"ip_is_private":true,"invert":true}"""), conditions[1])
            assertEquals("proxy", guard["outbound"].asString)
            assertEquals("and", guard["mode"].asString)
            assertTrue(route.indexOf(guard) > route.indexOfFirst { it["action"]?.asString == "hijack-dns" })
            assertTrue(route.indexOf(guard) < route.indexOfFirst { it.has("rule_set") })
            assertTrue(route.any { it["ip_is_private"]?.asBoolean == true && it["outbound"]?.asString == "direct" })
            // No port/protocol filter excludes ChatGPT's opaque UDP voice traffic.
            assertTrue(conditions.all { !it.has("port") && !it.has("protocol") && !it.has("network") })
        }
    }

    @Test
    fun allThreeEnginesPreserveBusinessPolicyAndSmartOffStillDisablesIt() {
        for (snapshot in snapshots()) for (smart in listOf(false, true)) {
            val system = config(snapshot, smart)
            for (adapted in listOf(RootConfigAdapter.adapt(system.toString()).configJson,
                HevConfigAdapter.adapt(system.toString()).configJson)) {
                val runtime = JsonParser.parseString(adapted).asJsonObject
                assertEquals(system["dns"], runtime["dns"])
                assertEquals(system["outbounds"], runtime["outbounds"])
                assertEquals(rules(system, "route"), rules(runtime, "route").drop(1))
                // HEV cannot carry Android package identity through SOCKS. Its common
                // domain/DNS policy is therefore essential; parity is not a UID test.
                assertEquals(smart, runtime.toString().contains("challenges.cloudflare.com"))
                assertEquals(smart, runtime.toString().contains(ChatGptRoutingPolicy.PACKAGE_NAME))
            }
        }
    }
}

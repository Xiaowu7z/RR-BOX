package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatRecoveryRuntimePolicyTest {
    private fun canonical(): String {
        val node = ProxyNode(id = "runtime-gate", tag = "runtime-gate", type = ProtocolType.SOCKS,
            server = "node.example.com", serverPort = 1080,
            rawJson = """{"type":"socks","server":"node.example.com","server_port":1080,"version":"5"}""")
        return ConfigBuilder.buildSingBoxConfig(node, listOf(node), emptyList())
    }

    private fun rules(text: String) = JsonParser.parseString(text).asJsonObject
        .getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }

    private fun wechatRules(text: String) = rules(text).filter {
        it["action"]?.asString == "route" && it["outbound"]?.asString == "direct" && it.has("override_address")
    }

    @Test
    fun disabledLiveCopyRemovesWechatCandidatesAndPreservesEverythingElse() {
        val source = canonical()
        val before = JsonParser.parseString(source).asJsonObject
        val disabled = WeChatRecoveryRuntimePolicy.apply(source, false)
        val after = JsonParser.parseString(disabled).asJsonObject
        assertEquals(13, wechatRules(source).size)
        assertTrue(wechatRules(disabled).isEmpty())
        val expectedRules = rules(source).filterNot { it in wechatRules(source) }
        assertEquals(expectedRules, rules(disabled))
        assertTrue(rules(disabled).any { it["action"]?.asString == "route-options" })
        before.getAsJsonObject("route").remove("rules")
        after.getAsJsonObject("route").remove("rules")
        assertEquals(before, after)
        assertEquals(disabled, WeChatRecoveryRuntimePolicy.apply(disabled, false))
    }

    @Test
    fun reapplyingToCanonicalRestoresCandidatesWhenPhysicalPathChanges() {
        val canonical = canonical()
        val healthyPath = WeChatRecoveryRuntimePolicy.apply(canonical, false)
        val missingIpv6Path = WeChatRecoveryRuntimePolicy.apply(canonical, true)
        assertFalse(healthyPath == canonical)
        assertEquals(canonical, missingIpv6Path)
        assertEquals(13, wechatRules(missingIpv6Path).size)
        for (runtime in listOf(RootConfigAdapter.adapt(healthyPath).configJson,
            HevConfigAdapter.adapt(healthyPath).configJson)) {
            assertTrue(wechatRules(runtime).isEmpty())
        }
    }

    @Test
    fun unrelatedOrIpv4OverridesAreNeverRemoved() {
        val source = JsonParser.parseString(canonical()).asJsonObject
        val routeRules = source.getAsJsonObject("route").getAsJsonArray("rules")
        val candidate = wechatRules(source.toString()).first()
        val unrelated = candidate.deepCopy().apply { addProperty("override_address", "other.example.com") }
        val ipv4 = candidate.deepCopy().apply {
            getAsJsonArray("rules").map { it.asJsonObject }.first { it.has("ip_version") }
                .addProperty("ip_version", 4)
        }
        routeRules.add(unrelated)
        routeRules.add(ipv4)
        val remaining = rules(WeChatRecoveryRuntimePolicy.apply(source.toString(), false))
        assertTrue(unrelated in remaining)
        assertTrue(ipv4 in remaining)
    }
}

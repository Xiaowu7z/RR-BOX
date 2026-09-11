package com.rr.client.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.vpn.HevTunnelConfig
import org.junit.Assert.*
import org.junit.Test

class HevAppRoutingAdapterTest {
    private fun source() = JsonParser.parseString("""
        {
          "inbounds":[{"type":"tun","include_package":["com.rr.client","org.telegram.messenger","com.openai.chatgpt"]}],
          "outbounds":[{"type":"direct","tag":"direct"},{"type":"socks","tag":"proxy"},{"type":"socks","tag":"rr-app-node-aGs"}],
          "route":{"final":"proxy","rules":[
            {"protocol":"dns","action":"hijack-dns"},
            {"package_name":["org.telegram.messenger"],"action":"route","outbound":"rr-app-node-aGs"},
            {"type":"logical","mode":"and","rules":[{"network":["tcp","udp"]},{"package_name_regex":[".+"],"invert":true}],"action":"reject"}
          ]},
          "dns":{"final":"dns-proxy","servers":[{"tag":"dns-proxy"},{"tag":"dns-rr-app-node-aGs","detour":"rr-app-node-aGs"}],
            "rules":[{"package_name":["org.telegram.messenger"],"server":"dns-rr-app-node-aGs"}]
          }
        }
    """).asJsonObject

    @Test
    fun telegramUsesSeparateAuthenticatedInboundWhileMainOutletAndScopeStayUnchanged() {
        val original = source()
        val runtime = HevConfigAdapter.adapt(original.toString())
        val adapted = JsonParser.parseString(runtime.configJson).asJsonObject
        assertTrue(runtime.appRouting.enabled)
        assertEquals(setOf("org.telegram.messenger"), runtime.appRouting.packagePorts.keys)
        assertTrue(runtime.appRouting.mainTargetPorts.isEmpty())
        assertEquals(listOf("com.openai.chatgpt", "org.telegram.messenger"), runtime.perAppPolicy.allowedPackages)
        assertEquals(original.get("outbounds"), adapted.get("outbounds"))
        assertEquals("proxy", adapted.getAsJsonObject("route").get("final").asString)
        val inbounds = adapted.getAsJsonArray("inbounds")
        assertEquals(2, inbounds.size())
        val selected = inbounds[1].asJsonObject
        assertEquals(runtime.appRouting.packagePorts.getValue("org.telegram.messenger"), selected.get("listen_port").asInt)
        assertNotEquals(HevConfigAdapter.SOCKS_PORT, selected.get("listen_port").asInt)
        inbounds.forEach { element ->
            val inbound = element.asJsonObject
            assertEquals("127.0.0.1", inbound.get("listen").asString)
            val user = inbound.getAsJsonArray("users")[0].asJsonObject
            assertEquals(runtime.appRouting.socksUsername, user.get("username").asString)
            assertEquals(runtime.appRouting.socksPassword, user.get("password").asString)
        }
        val rules = adapted.getAsJsonObject("route").getAsJsonArray("rules")
        assertFalse(rules.any { it.toString().contains("package_name_regex") })
        val binding = rules.last().asJsonObject
        assertEquals("rr-app-node-aGs", binding.get("outbound").asString)
        assertEquals(selected.get("tag").asString, binding.getAsJsonArray("inbound")[0].asString)
        assertFalse(binding.has("package_name"))
        val dns = adapted.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject
        assertEquals("dns-rr-app-node-aGs", dns.get("server").asString)
        assertEquals(selected.get("tag").asString, dns.getAsJsonArray("inbound")[0].asString)
        assertEquals(2, rules[0].asJsonObject.getAsJsonArray("inbound").size())
        val yaml = HevTunnelConfig.build(HevConfigAdapter.SOCKS_PORT, runtime.appRouting.socksUsername,
            runtime.appRouting.socksPassword)
        assertTrue(yaml.contains("password: '${runtime.appRouting.socksPassword}'"))
    }

    @Test
    fun deletedBoundNodeGetsRejectOutletAndNeverFallsBackToMain() {
        val input = source()
        input.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject.apply {
            addProperty("action", "reject")
            remove("outbound")
        }
        input.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject.apply {
            addProperty("action", "reject")
            remove("server")
        }
        val runtime = HevConfigAdapter.adapt(input.toString())
        assertTrue(runtime.appRouting.packagePorts.getValue("org.telegram.messenger") != HevConfigAdapter.SOCKS_PORT)
        val adapted = JsonParser.parseString(runtime.configJson).asJsonObject
        val reject = adapted.getAsJsonObject("route").getAsJsonArray("rules").last().asJsonObject
        assertEquals("reject", reject.get("action").asString)
        assertFalse(reject.has("outbound"))
        assertEquals("reject", adapted.getAsJsonObject("dns").getAsJsonArray("rules")[0].asJsonObject.get("action").asString)
    }

    @Test
    fun unselectedAppCannotAcquireAnOutletAndOldHevPathRemainsUnchanged() {
        val input = source()
        input.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("include_package").remove(1)
        val runtime = HevConfigAdapter.adapt(input.toString())
        assertFalse(runtime.appRouting.enabled)
        assertNull(runtime.appRouting.socksPassword)
        val inbounds = JsonParser.parseString(runtime.configJson).asJsonObject.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        assertFalse(inbounds[0].asJsonObject.has("users"))
    }

    @Test
    fun applicationsBoundToTheSameNodeShareOneOutletButDifferentRunsGetFreshSecrets() {
        val input = source()
        input.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject
            .getAsJsonArray("package_name").add("com.openai.chatgpt")
        val first = HevConfigAdapter.adapt(input.toString())
        val second = HevConfigAdapter.adapt(input.toString())
        assertEquals(2, first.appRouting.packagePorts.size)
        assertEquals(1, first.appRouting.packagePorts.values.toSet().size)
        assertNotEquals(first.appRouting.socksPassword, second.appRouting.socksPassword)
        assertEquals(43, first.appRouting.socksPassword!!.length)
    }

    @Test
    fun explicitMainNodeBindingStillHasAnOwnedInbound() {
        val input = source()
        input.getAsJsonObject("route").getAsJsonArray("rules")[1].asJsonObject.addProperty("outbound", "proxy")
        val runtime = HevConfigAdapter.adapt(input.toString())
        assertTrue(runtime.appRouting.enabled)
        assertNotEquals(HevConfigAdapter.SOCKS_PORT, runtime.appRouting.packagePorts.getValue("org.telegram.messenger"))
        assertEquals(setOf(runtime.appRouting.packagePorts.getValue("org.telegram.messenger")), runtime.appRouting.mainTargetPorts)
        val rules = JsonParser.parseString(runtime.configJson).asJsonObject.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals("proxy", rules.last().asJsonObject.get("outbound").asString)
    }
}

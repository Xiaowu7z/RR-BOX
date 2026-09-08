package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootConfigAdapterTest {
    private fun canonical(): JsonObject = JsonParser.parseString(
        """
        {
          "log": {"level": "warn", "timestamp": true},
          "inbounds": [
            {"type": "tun", "tag": "canonical-tun", "address": ["172.19.0.1/30"],
             "mtu": 9000, "stack": "gvisor", "auto_route": true, "strict_route": true},
            {"type": "socks", "tag": "extra-in", "listen": "127.0.0.1", "listen_port": 1080}
          ],
          "dns": {
            "servers": [
              {"type": "udp", "tag": "dns-direct", "server": "223.5.5.5", "server_port": 53},
              {"type": "tls", "tag": "dns-remote", "server": "1.1.1.1", "server_port": 853,
               "detour": "proxy", "tls": {"enabled": true, "server_name": "cloudflare-dns.com"}}
            ],
            "rules": [{"domain_suffix": ["example.cn"], "action": "route", "server": "dns-direct"}],
            "final": "dns-remote", "strategy": "prefer_ipv4", "reverse_mapping": true
          },
          "outbounds": [
            {"type": "vless", "tag": "proxy", "server": "proxy.example", "server_port": 443,
             "uuid": "00000000-0000-4000-8000-000000000000", "tls": {"enabled": true}},
            {"type": "direct", "tag": "direct", "domain_resolver": "dns-direct"}
          ],
          "route": {
            "rules": [
              {"action": "sniff"},
              {"protocol": "dns", "action": "hijack-dns"},
              {"package_name": ["com.example.app"], "outbound": "proxy"},
              {"domain_suffix": ["example.cn"], "outbound": "direct"},
              {"ip_is_private": true, "outbound": "direct"},
              {"rule_set": ["geoip-cn"], "outbound": "direct"}
            ],
            "rule_set": [{"type": "local", "tag": "geoip-cn", "format": "binary", "path": "/rules/cn.srs"}],
            "final": "proxy", "default_domain_resolver": "dns-direct", "auto_detect_interface": true
          },
          "experimental": {"cache_file": {"enabled": true}}
        }
        """.trimIndent()
    ).asJsonObject

    private fun JsonObject.tun(): JsonObject = getAsJsonArray("inbounds")[0].asJsonObject

    private fun adapted(source: JsonObject): JsonObject =
        JsonParser.parseString(RootConfigAdapter.adapt(source.toString()).configJson).asJsonObject

    @Test
    fun nativeTunKeepsCanonicalIdentityAndAllBusinessPolicies() {
        val source = canonical()
        val runtime = adapted(source)
        val tun = runtime.tun()

        assertEquals("tun", tun.get("type").asString)
        assertEquals("canonical-tun", tun.get("tag").asString)
        assertEquals(
            listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
            tun.getAsJsonArray("address").map { it.asString }
        )
        assertEquals(1500, tun.get("mtu").asInt)
        assertEquals("system", tun.get("stack").asString)
        assertFalse(tun.get("auto_route").asBoolean)
        assertFalse(tun.get("strict_route").asBoolean)
        assertEquals(source.getAsJsonArray("inbounds")[1], runtime.getAsJsonArray("inbounds")[1])

        // Restore precisely the owned TUN fields and remove the required resolver rule.
        // Equality then covers every route, outbound, DNS, rule-set and unknown field.
        listOf("address", "mtu", "stack", "auto_route", "strict_route").forEach { key ->
            tun.add(key, source.tun().get(key))
        }
        runtime.getAsJsonObject("route").getAsJsonArray("rules").remove(0)
        assertEquals(source, runtime)
    }

    @Test
    fun dns53HijackPrecedesPrivateRoutingAndCoversAllResolversOnlyOnRootInbound() {
        val source = canonical()
        val rules = adapted(source).getAsJsonObject("route").getAsJsonArray("rules")
        val resolver = rules[0].asJsonObject

        assertEquals(listOf("canonical-tun"), resolver.getAsJsonArray("inbound").map { it.asString })
        assertEquals("hijack-dns", resolver.get("action").asString)
        assertEquals(53, resolver.get("port").asInt)
        assertEquals(listOf("tcp", "udp"), resolver.getAsJsonArray("network").map { it.asString })
        assertFalse(resolver.has("ip_cidr"))
        assertFalse(resolver.has("protocol"))
        rules.remove(0)
        assertEquals(source.getAsJsonObject("route").get("rules"), rules)
    }

    @Test
    fun dns53HijackStillWorksWithOptionalSniffingAndDnsRulesDisabled() {
        val source = canonical().apply { getAsJsonObject("route").remove("rules") }
        val runtime = adapted(source)
        val rules = runtime.getAsJsonObject("route").getAsJsonArray("rules")

        assertEquals(1, rules.size())
        assertEquals("hijack-dns", rules[0].asJsonObject.get("action").asString)
        assertEquals(source.get("dns"), runtime.get("dns"))
        assertEquals(source.get("outbounds"), runtime.get("outbounds"))
    }

    @Test
    fun legacyLocalResolversUseExplicitUpstreamAndKeepTheirPolicyReferences() {
        val source = canonical()
        val servers = source.getAsJsonObject("dns").getAsJsonArray("servers")
        servers.add(JsonObject().apply {
            addProperty("type", "local")
            addProperty("tag", "legacy-local")
            addProperty("detour", "direct")
            addProperty("connect_timeout", "5s")
        })
        servers.add(JsonObject().apply {
            addProperty("type", "local")
            addProperty("tag", "legacy-backup")
        })
        source.getAsJsonObject("dns").addProperty("final", "legacy-local")
        val runtime = adapted(source)
        val runtimeServers = runtime.getAsJsonObject("dns").getAsJsonArray("servers")

        for (index in 2..3) {
            val resolver = runtimeServers[index].asJsonObject
            assertEquals("udp", resolver.get("type").asString)
            assertEquals("223.5.5.5", resolver.get("server").asString)
            assertEquals(53, resolver.get("server_port").asInt)
            assertEquals(servers[index].asJsonObject.get("tag"), resolver.get("tag"))
            resolver.addProperty("type", "local")
            resolver.remove("server")
            resolver.remove("server_port")
        }
        assertEquals(source.get("dns"), runtime.get("dns"))
        assertEquals(source.getAsJsonObject("route").get("rule_set"),
            runtime.getAsJsonObject("route").get("rule_set"))
    }

    @Test
    fun allowListIsNormalizedAndSelfIsRemovedBeforeNativeUidSelection() {
        val source = canonical().apply {
            tun().add("include_package", JsonArray().apply {
                add("org.telegram.messenger")
                add(" com.android.chrome ")
                add("com.rr.client")
                add("org.telegram.messenger")
                add("")
            })
        }
        val runtime = RootConfigAdapter.adapt(source.toString())

        assertEquals(listOf("com.android.chrome", "org.telegram.messenger"), runtime.perAppPolicy.allowedPackages)
        assertTrue(runtime.perAppPolicy.disallowedPackages.isEmpty())
        assertFalse(JsonParser.parseString(runtime.configJson).asJsonObject.tun().has("include_package"))
        assertTrue(source.tun().has("include_package"))
    }

    @Test
    fun excludeListAndAllAppsPolicyMatchNativeUidSelection() {
        val source = canonical().apply {
            tun().add("exclude_package", JsonArray().apply {
                add(" com.example.direct ")
                add("com.rr.client")
                add("com.example.direct")
            })
        }
        val runtime = RootConfigAdapter.adapt(source.toString())
        assertTrue(runtime.perAppPolicy.allowedPackages.isEmpty())
        assertEquals(listOf("com.example.direct"), runtime.perAppPolicy.disallowedPackages)
        assertFalse(JsonParser.parseString(runtime.configJson).asJsonObject.tun().has("exclude_package"))

        val all = RootConfigAdapter.adapt(canonical().toString()).perAppPolicy
        assertTrue(all.allowedPackages.isEmpty())
        assertTrue(all.disallowedPackages.isEmpty())
    }

    @Test
    fun conflictingPerAppListsAreRejected() {
        val source = canonical().apply {
            tun().add("include_package", JsonArray().apply { add("com.example.allow") })
            tun().add("exclude_package", JsonArray().apply { add("com.example.bypass") })
        }
        assertThrows(IllegalArgumentException::class.java) { RootConfigAdapter.adapt(source.toString()) }
    }

    @Test
    fun emptyOrSelfOnlyAllowListCannotBecomeAllApps() {
        listOf(emptyList(), listOf("", " "), listOf(" com.rr.client ")).forEach { packages ->
            val source = canonical().apply {
                tun().add("include_package", JsonArray().apply { packages.forEach(::add) })
            }
            assertThrows(IllegalArgumentException::class.java) { RootConfigAdapter.adapt(source.toString()) }
        }
    }

    @Test
    fun malformedPerAppListsAreRejected() {
        listOf("\"com.example.app\"", "null", "[false]", "[null]", "[{}]").forEach { invalid ->
            val source = canonical().apply { tun().add("exclude_package", JsonParser.parseString(invalid)) }
            assertThrows(IllegalArgumentException::class.java) { RootConfigAdapter.adapt(source.toString()) }
        }
    }

    @Test
    fun absentAmbiguousOrUntaggedTunIsRejected() {
        val missing = canonical().apply { getAsJsonArray("inbounds").remove(0) }
        val duplicate = canonical().apply { getAsJsonArray("inbounds").add(tun().deepCopy()) }
        val untagged = canonical().apply { tun().remove("tag") }
        val noRoute = canonical().apply { remove("route") }
        listOf(missing, duplicate, untagged, noRoute).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { RootConfigAdapter.adapt(source.toString()) }
        }
    }
}

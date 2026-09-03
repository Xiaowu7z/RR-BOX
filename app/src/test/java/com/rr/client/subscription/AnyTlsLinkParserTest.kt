package com.rr.client.subscription

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AnyTlsLinkParserTest {
    @Test
    fun parsesRrVpsAnyTlsShareLink() {
        val link = "anytls://11111111-2222-3333-4444-555555555555@154.26.184.195:8445?sni=www.bing.com&insecure=1#ANYTLS-DMIT-LAX"
        val node = AnyTlsLinkParser.parseLink(link, "rr", "RR-vps") ?: error("AnyTLS not parsed")
        assertEquals(ProtocolType.ANYTLS, node.type)
        assertEquals("154.26.184.195", node.server)
        assertEquals(8445, node.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuidOrPassword)
        assertEquals("www.bing.com", node.sni)
        assertEquals("ANYTLS-DMIT-LAX", node.tag)
        val raw = JsonParser.parseString(node.rawJson).asJsonObject
        assertEquals("anytls", raw.get("type").asString)
        assertTrue(raw.getAsJsonObject("tls").get("enabled").asBoolean)
        assertTrue(raw.getAsJsonObject("tls").get("insecure").asBoolean)
    }

    @Test
    fun recoversAnyTlsFromBase64Subscription() {
        val decoded = listOf(
            "vless://test@example.com:443?security=tls#VLESS",
            "anytls://secret@example.com:8445?sni=edge.example.com#AnyTLS"
        ).joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(decoded.toByteArray(StandardCharsets.UTF_8))
        val nodes = AnyTlsLinkParser.extractFromContent(encoded, "p1", "subscription")
        assertEquals(1, nodes.size)
        assertEquals(ProtocolType.ANYTLS, nodes.single().type)
        assertEquals("example.com", nodes.single().server)
        assertEquals(8445, nodes.single().serverPort)
    }

    @Test
    fun acceptsBracketedIpv6Endpoint() {
        val node = AnyTlsLinkParser.parseLink(
            "anytls://password@[2001:db8::8]:443?sni=example.com#IPv6",
            "p2",
            "subscription"
        ) ?: error("IPv6 AnyTLS not parsed")
        assertEquals("2001:db8::8", node.server)
        assertEquals(443, node.serverPort)
        assertEquals("example.com", node.sni)
    }
}

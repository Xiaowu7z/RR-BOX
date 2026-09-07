package com.rr.client.sharing

import com.google.gson.JsonParser
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.AnyTlsLinkParser
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SharePayloadBuilderTest {
    private fun base(type: ProtocolType = ProtocolType.VLESS_REALITY) = ProxyNode(
        id = "test", tag = "洛杉矶 #1 / 测试", type = type, server = "2001:db8::7", serverPort = 8443,
        uuidOrPassword = "f0cfbd86-37a4-4a36-9694-7d6a8b10f693", sni = "edge.example",
        realityPublicKey = "public-key", realityShortId = "abcdef", flow = "xtls-rprx-vision"
    )

    private fun payload(node: ProxyNode): SharePayload =
        (SharePayloadBuilder.node(node) as SharePayloadResult.Success).payload

    private fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
    private fun query(link: String): Map<String, String> = URI(link).rawQuery.orEmpty().split('&')
        .filter(String::isNotEmpty).associate { it.substringBefore('=') to decode(it.substringAfter('=')) }

    @Test fun realityLinkRetainsIpv6FlowSniKeysAndUnicodeTitle() {
        val node = base()
        val result = payload(node)
        val uri = URI(result.text)
        assertEquals(SharePayloadKind.LINK, result.kind)
        assertEquals("[2001:db8::7]", uri.host)
        assertEquals(8443, uri.port)
        assertEquals(node.tag, decode(uri.rawFragment))
        assertEquals(node.uuidOrPassword, decode(uri.rawUserInfo))
        assertEquals(mapOf("security" to "reality", "fp" to "chrome", "pbk" to "public-key",
            "sid" to "abcdef", "sni" to "edge.example", "flow" to "xtls-rprx-vision"),
            query(result.text).filterKeys { it in setOf("security", "fp", "pbk", "sid", "sni", "flow") })
    }

    @Test fun websocketEscapesReservedCharactersWithoutChangingValues() {
        val node = base(ProtocolType.VLESS_TLS).copy(network = "ws", path = "/edge?a=1&b=two words#part", host = "cdn.example")
        val q = query(payload(node).text)
        assertEquals(node.path, q["path"])
        assertEquals(node.host, q["host"])
        assertEquals("tls", q["security"])
        assertEquals("ws", q["type"])
    }

    @Test fun grpcProvidesStandardServiceNameAndExistingImporterPath() {
        val q = query(payload(base(ProtocolType.VLESS_TLS).copy(network = "grpc", path = "service/a b")).text)
        assertEquals("service/a b", q["serviceName"])
        assertEquals(q["serviceName"], q["path"])
    }

    @Test fun vmessDecodesToCompleteReadableV2Payload() {
        val node = base(ProtocolType.VMESS_WS_ARGO).copy(network = "ws", path = "/a?key=one&two", host = "cdn.example", alpn = "h2,http/1.1", allowInsecure = true)
        val text = payload(node).text
        val json = JsonParser.parseString(String(Base64.getDecoder().decode(text.removePrefix("vmess://")), Charsets.UTF_8)).asJsonObject
        assertEquals(node.uuidOrPassword, json["id"].asString)
        assertEquals(node.path, json["path"].asString)
        assertEquals(node.alpn, json["alpn"].asString)
        assertEquals(node.tag, json["ps"].asString)
        assertTrue(json["allowInsecure"].asBoolean)
        assertEquals("chrome", json["fp"].asString)
    }

    @Test fun hysteriaPasswordAndObfsSurviveUriEncoding() {
        val password = "p:a@ss/+?# 中文"
        val node = base(ProtocolType.HYSTERIA2).copy(uuidOrPassword = password, obfs = "salamander", obfsPassword = "b&c=d", allowInsecure = true)
        val result = payload(node)
        assertEquals(password, decode(URI(result.text).rawUserInfo))
        assertEquals("b&c=d", query(result.text)["obfs-password"])
        assertEquals("1", query(result.text)["insecure"])
    }

    @Test fun portHoppingUsesJsonWithoutLosingRanges() {
        val result = payload(base(ProtocolType.HYSTERIA2).copy(hoppingPorts = "443,10000-10100"))
        assertEquals(SharePayloadKind.JSON, result.kind)
        val json = JsonParser.parseString(result.text).asJsonObject
        assertEquals(listOf("443", "10000:10100"), json["server_ports"].asJsonArray.map { it.asString })
        assertFalse(json.has("server_port"))
    }

    @Test fun tuicKeepsBothCredentialsAndRuntimeDefaults() {
        val node = base(ProtocolType.TUIC_V5).copy(extraPassword = "secret:@/+中文")
        val result = payload(node)
        assertEquals(node.uuidOrPassword + ":" + node.extraPassword, decode(URI(result.text).rawUserInfo))
        assertEquals("bbr", query(result.text)["congestion_control"])
        assertEquals("native", query(result.text)["udp_relay_mode"])
    }

    @Test fun trojanAndShadowsocksKeepReservedPasswordCharacters() {
        val password = "a:b@c/+中文"
        val trojan = payload(base(ProtocolType.TROJAN).copy(uuidOrPassword = password))
        assertEquals(password, decode(URI(trojan.text).rawUserInfo))
        val ss = payload(base(ProtocolType.SHADOWSOCKS).copy(uuidOrPassword = password, ssMethod = "aes-128-gcm"))
        assertEquals("aes-128-gcm:$password", String(Base64.getUrlDecoder().decode(URI(ss.text).rawUserInfo), Charsets.UTF_8))
        val ss2022 = payload(base(ProtocolType.SHADOWSOCKS).copy(uuidOrPassword = "key+/=", ssMethod = "2022-blake3-aes-128-gcm"))
        assertEquals("2022-blake3-aes-128-gcm:key+/=", decode(URI(ss2022.text).rawUserInfo))
    }

    @Test fun rawAnyTlsAndAuthenticatedSocksAreShareable() {
        val anyTls = payload(base(ProtocolType.ANYTLS).copy(rawJson = """{"type":"anytls","server":"edge.example","server_port":443,"password":"p@ss","tls":{"enabled":true,"server_name":"sni.example","insecure":true}}"""))
        assertEquals(SharePayloadKind.LINK, anyTls.kind)
        assertTrue(anyTls.text.startsWith("anytls://"))
        assertEquals("sni.example", query(anyTls.text)["sni"])
        val socks = payload(base(ProtocolType.SOCKS).copy(rawJson = """{"type":"socks","server":"proxy.example","server_port":1081,"version":"5","username":"u@ser","password":"p:a/ss"}"""))
        assertEquals("u@ser:p:a/ss", decode(URI(socks.text).rawUserInfo))
        assertEquals(1081, URI(socks.text).port)
    }

    @Test fun httpPreservesTlsAndCredentialsWithoutInventingProxyParameters() {
        val node = base(ProtocolType.HTTP).copy(rawJson = """{"type":"http","server":"proxy.example","server_port":9443,"username":"user","password":"pass","tls":{"enabled":true,"server_name":"sni.example","alpn":["h2"]}}""")
        val result = payload(node)
        assertEquals("https", URI(result.text).scheme)
        assertEquals("sni.example", query(result.text)["sni"])
        assertEquals("h2", query(result.text)["alpn"])
    }

    @Test fun rawUnknownFieldsAndFormattingAreRetainedExactly() {
        val raw = """{
 "type":"vless", "server":"edge.example", "server_port":443,
 "uuid":"raw-credential", "future_option":{"secret":"do-not-drop"}, "detour":"other"
}"""
        val result = payload(base().copy(rawJson = raw))
        assertEquals(SharePayloadKind.JSON, result.kind)
        assertEquals(raw, result.text)
        assertEquals("application/json", result.mimeType)
    }

    @Test fun nestedHeadersAndTlsOptionsCannotBeSilentlyDropped() {
        for (extra in listOf(
            """"transport":{"type":"ws","path":"/","headers":{"Host":"host.example","Authorization":"Bearer secret"}}""",
            """"tls":{"enabled":true,"certificate":["certificate-data"]}""",
            """"tls":{"enabled":true,"utls":{"enabled":true,"fingerprint":"firefox"}}"""
        )) {
            val raw = """{"type":"vless","server":"edge.example","server_port":443,"uuid":"secret",$extra}"""
            assertEquals(raw, payload(base().copy(rawJson = raw)).text)
        }
    }

    @Test fun rawIsAuthoritativeOverStaleModelCredentialsAndEndpoint() {
        val result = payload(base().copy(rawJson = """{"type":"vless","server":"raw.example","server_port":1234,"uuid":"raw-secret"}"""))
        assertEquals("raw.example", URI(result.text).host)
        assertEquals(1234, URI(result.text).port)
        assertEquals("raw-secret", decode(URI(result.text).rawUserInfo))
        assertFalse(result.text.contains("public-key"))
    }

    @Test fun malformedRawAndUnsupportedModelFailInsteadOfRebuildingLossily() {
        assertTrue(SharePayloadBuilder.node(base().copy(rawJson = "broken")) is SharePayloadResult.Failure)
        assertTrue(SharePayloadBuilder.node(base(ProtocolType.WIREGUARD)) is SharePayloadResult.Failure)
        assertTrue(SharePayloadBuilder.node(base().copy(network = "new-transport")) is SharePayloadResult.Failure)
    }

    @Test fun informationPlaceholderCannotBeSharedAsConnection() {
        val node = base().copy(tag = "流量信息(勿选) | 剩余 99 GB", server = "127.0.0.1", serverPort = 9)
        assertTrue(SharePayloadBuilder.node(node) is SharePayloadResult.Failure)
        assertTrue(SharePayloadBuilder.node(node.copy(server = "real.example", serverPort = 443)) is SharePayloadResult.Success)
    }

    @Test fun subscriptionPreservesSignedUrlBytesExactly() {
        val original = "HTTPS://sub.example:8443/a%2Fb?token=a%2Bb%2F%3D&x=1&x=2#name"
        val result = SharePayloadBuilder.subscription("订阅名称", original) as SharePayloadResult.Success
        assertEquals(original, result.payload.text)
        assertEquals("订阅名称", result.payload.title)
        assertEquals(SharePayloadKind.LINK, result.payload.kind)
    }

    @Test fun subscriptionRejectsInvalidOrAmbiguousAddresses() {
        for (url in listOf("", " ", "file:///etc/passwd", "https://", "https://sub.example:0/path", "https://sub.example:99999/path", " https://sub.example/path", "https://sub.example/a\n")) {
            assertTrue(SharePayloadBuilder.subscription("name", url) is SharePayloadResult.Failure)
        }
    }

    @Test fun legacySubscriptionWithoutSchemeKeepsOriginalRatherThanGuessingHttps() {
        val original = "sub.example:8080/path?token=encoded%2Bvalue"
        val result = SharePayloadBuilder.subscription("name", original) as SharePayloadResult.Success
        assertEquals(original, result.payload.text)
        assertNotNull(result.payload.notice)
    }

    @Test fun explicitRenameChangesOnlyRawTagAndPreservesAllOtherFields() {
        val raw = """{"type":"custom","tag":"old","server":"edge.example","server_port":443,"password":"secret","extension":{"preserve":true}}"""
        val node = base(ProtocolType.CUSTOM).copy(rawJson = raw, tag = "new", nameOverrideOnly = true)
        val result = payload(node)
        val expected = JsonParser.parseString(raw).asJsonObject.apply { addProperty("tag", "new") }
        assertEquals(expected, JsonParser.parseString(result.text))
    }

    @Test fun payloadLimitCountsUtf8BytesInsteadOfCharacters() {
        val url = "https://sub.example/?token=" + "中".repeat(SharePayloadBuilder.MAX_PAYLOAD_BYTES / 3)
        assertTrue(url.length < SharePayloadBuilder.MAX_PAYLOAD_BYTES)
        assertTrue(SharePayloadBuilder.subscription("name", url) is SharePayloadResult.Failure)
        val raw = " ".repeat(SharePayloadBuilder.MAX_PAYLOAD_BYTES) + "{}"
        assertTrue(SharePayloadBuilder.node(base().copy(rawJson = raw)) is SharePayloadResult.Failure)
    }

    @Test fun anyTlsShareThroughRealImporterKeepsRuntimeOutboundExactly() {
        val node = base(ProtocolType.ANYTLS).copy(rawJson = """{"type":"anytls","server":"2001:db8::9","server_port":9443,"password":"p:a@ss/+?中文","tls":{"enabled":true,"server_name":"sni.example","insecure":true,"alpn":["h2","http/1.1"]}}""")
        val share = payload(node)
        assertEquals(SharePayloadKind.LINK, share.kind)
        val imported = AnyTlsLinkParser.parseLink(share.text, "new-id", "imported")!!
        fun outbound(value: ProxyNode) = JsonParser.parseString(ConfigBuilder.buildSingBoxConfig(
            selectedNode = value, allNodes = listOf(value), appRoutes = emptyList(), smartRouting = false
        )).asJsonObject.getAsJsonArray("outbounds").first { it.asJsonObject["tag"].asString == "proxy" }
        assertEquals(outbound(node), outbound(imported))
        assertEquals(node.tag, imported.tag)
    }

    @Test fun literalPercentCredentialsAndTitlesStayJsonForExistingAndroidImporters() {
        val password = "keep%2Fthis%25exactly"
        val result = payload(base(ProtocolType.TROJAN).copy(uuidOrPassword = password))
        assertEquals(SharePayloadKind.JSON, result.kind)
        assertEquals(password, JsonParser.parseString(result.text).asJsonObject["password"].asString)
        assertEquals(SharePayloadKind.JSON, payload(base().copy(tag = "name%2Funchanged")).kind)
    }
}

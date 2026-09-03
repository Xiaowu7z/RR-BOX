package com.rr.client.subscription

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashSubscriptionConverterTest {
    @Test
    fun convertsVlessRealityAndHysteria2() {
        val yaml = """
            proxies:
              - name: Reality HK
                type: vless
                server: 192.0.2.10
                port: 443
                uuid: 00000000-0000-4000-8000-000000000000
                flow: xtls-rprx-vision
                tls: true
                servername: www.example.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: TESTPUBLICKEY
                  short-id: abcd1234
              - name: HY2 JP
                type: hysteria2
                server: 198.51.100.8
                port: 443
                password: pass
                sni: example.org
                skip-cert-verify: true
        """.trimIndent()

        val converted = ClashSubscriptionConverter.convert(yaml)
        assertNotNull(converted)
        val outbounds = JsonParser.parseString(converted).asJsonObject.getAsJsonArray("outbounds")
        assertEquals(2, outbounds.size())
        val vless = outbounds[0].asJsonObject
        assertEquals("vless", vless.get("type").asString)
        assertTrue(vless.getAsJsonObject("tls").getAsJsonObject("reality").get("enabled").asBoolean)
        val hy2 = outbounds[1].asJsonObject
        assertEquals("hysteria2", hy2.get("type").asString)
        assertTrue(hy2.getAsJsonObject("tls").get("insecure").asBoolean)
    }

    @Test
    fun ignoresNonNodeClashSections() {
        val yaml = """
            mixed-port: 7890
            mode: rule
            rules:
              - MATCH,DIRECT
        """.trimIndent()
        assertEquals(null, ClashSubscriptionConverter.convert(yaml))
    }
}

package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class RawCredentialEditsTest {
    @Test fun passwordEditsReachRawHttpSocksAndShadowsocks() {
        listOf("http", "socks", "shadowsocks", "shadowtls").forEach { type ->
            val base = ProxyNode(id = "x", tag = "x", type = ProtocolType.CUSTOM,
                server = "example.com", serverPort = 443, uuidOrPassword = "old",
                rawJson = """{"type":"$type","server":"example.com","server_port":443,"password":"old","custom":true}""")
            val result = NodeOverridePatcher.apply(base, base.copy(uuidOrPassword = "new"))
            val raw = JsonParser.parseString(result.rawJson).asJsonObject
            assertEquals(type, "new", raw["password"].asString)
            assertTrue(raw["custom"].asBoolean)
        }
    }
    @Test fun shadowsocksMethodIsPatchedWithoutLosingRawFields() {
        val base = ProxyNode(id="x", tag="x", type=ProtocolType.SHADOWSOCKS, server="example.com", serverPort=443,
            ssMethod="aes-128-gcm", rawJson="""{"type":"shadowsocks","method":"aes-128-gcm"}""")
        val result = NodeOverridePatcher.apply(base, base.copy(ssMethod="chacha20-ietf-poly1305"))
        assertEquals("chacha20-ietf-poly1305", JsonParser.parseString(result.rawJson).asJsonObject["method"].asString)
    }
}

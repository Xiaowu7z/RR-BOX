package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeToolsTest {
    @Test
    fun parsesAndroidPingLatency() {
        val output = "64 bytes from 154.26.184.195: icmp_seq=1 ttl=51 time=37.6 ms"
        assertEquals(NodeLatencyState.Success(38L), NodeLatencyTester.parsePingOutput(output))
    }

    @Test
    fun patchesEditedRealityNodeWithoutDroppingUnknownFields() {
        val raw = """
            {
              "type":"vless",
              "tag":"VLESS-0",
              "server":"154.26.184.195",
              "server_port":28759,
              "uuid":"00000000-0000-4000-8000-000000000000",
              "flow":"xtls-rprx-vision",
              "packet_encoding":"xudp",
              "tls":{
                "enabled":true,
                "server_name":"www.example.com",
                "reality":{
                  "enabled":true,
                  "public_key":"old-key",
                  "short_id":"old-id"
                }
              }
            }
        """.trimIndent()

        val original = ProxyNode(
            id = "node-1",
            tag = "VLESS-0",
            type = ProtocolType.VLESS_REALITY,
            server = "154.26.184.195",
            serverPort = 28759,
            uuidOrPassword = "00000000-0000-4000-8000-000000000000",
            flow = "xtls-rprx-vision",
            realityPublicKey = "old-key",
            realityShortId = "old-id",
            sni = "www.example.com",
            rawJson = raw
        )
        val edited = original.copy(
            tag = "我的 Reality",
            serverPort = 28888,
            sni = "www.apple.com",
            realityPublicKey = "new-key"
        )

        val patched = NodeOverridePatcher.apply(original, edited)
        val obj = JsonParser.parseString(patched.rawJson).asJsonObject

        assertEquals("我的 Reality", obj.get("tag").asString)
        assertEquals(28888, obj.get("server_port").asInt)
        assertEquals("xudp", obj.get("packet_encoding").asString)
        assertEquals("www.apple.com", obj.getAsJsonObject("tls").get("server_name").asString)
        assertEquals(
            "new-key",
            obj.getAsJsonObject("tls").getAsJsonObject("reality").get("public_key").asString
        )
        assertTrue(obj.getAsJsonObject("tls").getAsJsonObject("reality").get("enabled").asBoolean)
        assertFalse(patched.rawJson.isBlank())
    }
}

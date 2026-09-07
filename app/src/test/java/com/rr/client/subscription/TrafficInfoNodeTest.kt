package com.rr.client.subscription

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class TrafficInfoNodeTest {
    private fun node(
        tag: String = "流量信息(勿选) | 已用103.51GB | 剩余896.49GB | 总量1000GB | 到期2026-10-01",
        server: String = "127.0.0.1",
        port: Int = 9
    ) = ProxyNode("info", tag, ProtocolType.VMESS_TLS, server, port)

    @Test fun extractsProviderTextWithoutChangingPrecisionOrDates() {
        val source = node()
        val info = TrafficInfoNode.parse(source)!!
        assertEquals(source.tag, info.originalText)
        assertEquals("103.51GB", info.usedText)
        assertEquals("896.49GB", info.remainingText)
        assertEquals("1000GB", info.totalText)
        assertEquals("2026-10-01", info.expiryText)
    }

    @Test fun handlesChinesePunctuationAndKeepsUnrecognizedFields() {
        val source = node("流量信息（请勿选择）｜已用：1.50 GiB｜总流量：2 TiB｜重置：每月 1 日｜公告：欢迎使用")
        val info = TrafficInfoNode.parse(source)!!
        assertEquals("1.50 GiB", info.usedText)
        assertEquals("2 TiB", info.totalText)
        assertNull(info.remainingText)
        assertTrue(info.detailText.contains("重置：每月 1 日｜公告：欢迎使用"))
        assertEquals(source.tag, info.originalText)
    }

    @Test fun neverTreatsTrafficWordsInOrdinaryNamesAsInformation() {
        listOf("香港剩余流量专线", "流量信息", "剩余：100GB", "到期2026-10-01", "香港 流量信息(勿选)")
            .forEach { assertNull(it, TrafficInfoNode.parse(node(it))) }
    }

    @Test fun requiresBothDiscardPortAndPlaceholderEndpoint() {
        listOf("proxy.example.com", "127.0.0.1.example.com", "127.999.0.1", "192.168.1.1", "8.8.8.8")
            .forEach { assertFalse(it, TrafficInfoNode.isInfoNode(node(server = it))) }
        listOf(0, 1, 80, 443, 1080, 65535).forEach {
            assertFalse("port=$it", TrafficInfoNode.isInfoNode(node(port = it)))
        }
    }

    @Test fun acceptsExplicitLoopbackAndUnspecifiedDiscardAddresses() {
        listOf("127.0.0.1", "127.0.1.2", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1", "0.0.0.0")
            .forEach { assertTrue(it, TrafficInfoNode.isInfoNode(node(server = it))) }
    }

    @Test fun missingValuesRemainMissingAndUnknownTextRemainsAvailable() {
        val source = node("流量信息(勿选) | 服务商暂未提供流量数据")
        val info = TrafficInfoNode.parse(source)!!
        assertEquals("服务商暂未提供流量数据", info.detailText)
        assertEquals(source.tag, info.originalText)
        assertNull(info.usedText)
        assertNull(info.remainingText)
        assertNull(info.totalText)
        assertNull(info.expiryText)
    }

    @Test fun keepsUnitsAndUnlimitedValuesAsReportedWithoutArithmetic() {
        listOf("0 B", "23 KB", "0.75 MB", "3GB", "4 TB", "7 KiB", "8MiB", "9 GiB", "10 TiB", "不限", "100")
            .forEach { reported ->
                val info = TrafficInfoNode.parse(node("流量信息(勿选) | 总量：$reported"))!!
                assertEquals(reported, info.totalText)
                assertNull(info.remainingText)
                assertNull(info.usedText)
            }
    }

    @Test fun conflictingFieldsDoNotInventAnAuthoritativeValue() {
        val source = node("流量信息(勿选) | 剩余10GB | 剩余20GB | 已用1GB | 已用1GB")
        val info = TrafficInfoNode.parse(source)!!
        assertNull(info.remainingText)
        assertEquals("1GB", info.usedText)
        assertEquals(source.tag, info.originalText)
        assertTrue(info.detailText.contains("剩余10GB | 剩余20GB"))
    }

    @Test fun markerMustEndBeforePayloadAndDoesNotConsumeUnknownText() {
        assertNull(TrafficInfoNode.parse(node("流量信息(勿选)香港真实节点")))
        val info = TrafficInfoNode.parse(node("流量信息(勿选)"))!!
        assertEquals("", info.detailText)
        assertEquals("流量信息(勿选)", info.originalText)
    }
}

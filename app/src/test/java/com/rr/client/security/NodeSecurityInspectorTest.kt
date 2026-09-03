package com.rr.client.security

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSecurityInspectorTest {
    @Test
    fun anyTlsWithInsecureIsHighRisk() {
        val node = ProxyNode(
            id = "1",
            tag = "AnyTLS",
            type = ProtocolType.ANYTLS,
            server = "example.com",
            serverPort = 443,
            uuidOrPassword = "secret",
            sni = "example.com",
            rawJson = """{"type":"anytls","server":"example.com","server_port":443,"password":"secret","tls":{"enabled":true,"server_name":"example.com","insecure":true}}"""
        )
        val report = NodeSecurityInspector.inspect(node)
        assertEquals(NodeSecurityRating.HIGH_RISK, report.rating)
        assertTrue(report.findings.any { it.title == "证书校验已关闭" && it.severity == NodeSecuritySeverity.DANGER })
    }

    @Test
    fun realityWithPublicKeyHasNoDangerFinding() {
        val node = ProxyNode(
            id = "2",
            tag = "Reality",
            type = ProtocolType.VLESS_REALITY,
            server = "203.0.113.9",
            serverPort = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            realityPublicKey = "public-key",
            sni = "www.cloudflare.com"
        )
        val report = NodeSecurityInspector.inspect(node)
        assertTrue(report.findings.none { it.severity == NodeSecuritySeverity.DANGER })
    }

    @Test
    fun weakShadowsocksCipherIsHighRisk() {
        val node = ProxyNode(
            id = "3",
            tag = "SS",
            type = ProtocolType.SHADOWSOCKS,
            server = "example.com",
            serverPort = 8388,
            uuidOrPassword = "secret",
            ssMethod = "rc4-md5"
        )
        val report = NodeSecurityInspector.inspect(node)
        assertEquals(NodeSecurityRating.HIGH_RISK, report.rating)
        assertTrue(report.findings.any { it.title == "弱 Shadowsocks 加密" })
    }
}

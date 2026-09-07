package com.rr.client.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRouteLogTest {
    private fun connection() = ConnectionRouteRecord(
        application = "微信", packages = listOf("com.tencent.mm"), uid = 10001,
        domain = "example.test", destination = "203.0.113.7:443", network = "tcp",
        outbound = "direct", outboundType = "direct", rule = "domain_suffix=example.test", hev = false
    )

    @Test fun usesActualMetadataForIdentityProtocolTargetOutboundAndRule() {
        val message = ConnectionRouteLog.format(connection())
        listOf("微信", "com.tencent.mm", "TCP", "example.test", "203.0.113.7:443", "直连", "命中：domain_suffix=example.test")
            .forEach { assertTrue(message, message.contains(it)) }
    }

    @Test fun hevNeverPresentsProxyProcessAsOriginalApplication() {
        val message = ConnectionRouteLog.format(connection().copy(
            application = "RRBOX", packages = listOf("com.rr.client"), hev = true,
            outbound = "proxy", outboundType = "vless", rule = ""
        ))
        assertTrue(message.contains("未知应用（HEV"))
        assertFalse(message.contains("RRBOX"))
        assertFalse(message.contains("com.rr.client"))
        assertFalse(message.contains("10001"))
        assertTrue(message.contains("代理 (proxy / vless)"))
        assertFalse(message.contains("命中"))
    }

    @Test fun unknownIdentityIsExplicitAndNeverGuessedFromDomain() {
        val message = ConnectionRouteLog.format(connection().copy(application = null, packages = emptyList(), uid = null))
        assertTrue(message.contains("未知应用"))
        assertFalse(message.contains("微信"))
    }

    @Test fun uriCredentialsPathsQueriesAndFragmentsAreNotRetained() {
        val message = ConnectionRouteLog.format(connection().copy(
            domain = "https://username:secret@example.test/private-video-token?access_token=abc#private",
            destination = "socks5://user:password@203.0.113.7:443/private-secret?key=secret"
        ))
        assertTrue(message.contains("example.test"))
        assertTrue(message.contains("203.0.113.7:443"))
        listOf("username", "secret", "private", "abc", "password", "user:")
            .forEach { assertFalse(message, message.contains(it)) }
    }

    @Test fun quietDisabledMissingAndMalformedConfigurationDoNotEnableCollection() {
        listOf("warn", "error", "fatal", "panic", "").forEach { level ->
            assertFalse(ConnectionRouteLog.enabledForConfig("""{"log":{"level":"$level"}}"""))
        }
        assertFalse(ConnectionRouteLog.enabledForConfig("""{"log":{"level":"info","disabled":true}}"""))
        assertFalse(ConnectionRouteLog.enabledForConfig("{}"))
        assertFalse(ConnectionRouteLog.enabledForConfig("invalid"))
        assertTrue(ConnectionRouteLog.enabledForConfig("""{"log":{"level":"info"}}"""))
    }

    @Test fun boundedIdHistoryDeduplicatesSnapshotsAndAllowsNewSession() {
        val ids = ConnectionLogDeduplicator(2)
        assertTrue(ids.accept("one"))
        assertFalse(ids.accept("one"))
        assertTrue(ids.accept("two"))
        assertTrue(ids.accept("three"))
        assertTrue(ids.accept("one"))
        assertFalse(ids.accept(""))
        ids.clear()
        assertTrue(ids.accept("one"))
    }

    @Test fun ipv6EndpointIsPreserved() {
        assertEquals("[2001:db8::1]:443", ConnectionRouteLog.endpointOnly("[2001:db8::1]:443"))
    }
}

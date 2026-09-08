package com.rr.client.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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
        assertTrue(message.contains("核心未提供具体规则"))
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

    @Test fun sessionsAndIndividualRoutesDistinguishRootFromSystemUsingTheStartedConfig() {
        val logs = ConnectionLogTracker()
        val config = """{"inbounds":[{"type":"tun","stack":"system"}]}"""
        val rootStart = logs.sessionStartedMessage(config, true, "hev-socks-in")
        assertTrue(rootStart.contains("核心引擎：ROOT；TUN 栈：system"))
        assertTrue(rootStart.contains("数据面就绪以启动结果为准"))
        val rootSession = logs.sessionId
        val observation = ConnectionLogObservation("one", connection())
        assertTrue(logs.format(observation, 1)!!.message.contains("入口：ROOT / TUN（system 栈）"))
        assertTrue(logs.sessionStoppedMessage().contains("核心引擎：ROOT"))

        logs.clear()
        val systemStart = logs.sessionStartedMessage(config, false, "hev-socks-in")
        assertTrue(systemStart.contains("核心引擎：SYSTEM；TUN 栈：system"))
        assertFalse(systemStart.contains("ROOT"))
        assertNotEquals(rootSession, logs.sessionId)
        assertNull(logs.format(observation.copy(closed = true), 2, rootSession))
        assertTrue(logs.format(observation, 3)!!.message.contains("入口：SYSTEM / TUN（system 栈）"))
    }

    @Test fun runtimeContextRequiresTheActualHevInboundAndNeverCopiesArbitraryConfigValues() {
        val hevConfig = """{"inbounds":[{"type":"socks","tag":"hev-socks-in"}]}"""
        val logs = ConnectionLogTracker()
        assertTrue(logs.sessionStartedMessage(hevConfig, false, "hev-socks-in").contains("核心引擎：HEV"))
        val message = logs.format(ConnectionLogObservation("hev", connection().copy(hev = true)), 1)!!.message
        assertTrue(message.contains("入口：HEV / SOCKS"))
        assertTrue(message.contains("未知应用（HEV"))

        val unrelatedSocks = ConnectionLogRuntime.fromConfig(
            """{"inbounds":[{"type":"socks","tag":"manual-proxy"}]}""", false, "hev-socks-in")
        assertEquals("UNKNOWN", unrelatedSocks.engine)
        val secretStack = ConnectionLogRuntime.fromConfig(
            """{"inbounds":[{"type":"tun","stack":"password=secret-token"}]}""", true, "hev-socks-in")
        assertTrue(secretStack.summary.contains("TUN 栈：未提供"))
        assertFalse(secretStack.summary.contains("secret-token"))
        assertEquals("UNKNOWN", ConnectionLogRuntime.fromConfig("invalid", false, "hev-socks-in").engine)
        assertEquals("ROOT", ConnectionLogRuntime.fromConfig("invalid", true, "hev-socks-in").engine)
    }

    @Test fun sameConnectionGetsOneRouteAndOneEndButNoRepeatedSnapshots() {
        val logs = ConnectionLogTracker()
        val start = ConnectionLogObservation("one", connection(), createdAt = 100, uplinkTotal = 0, downlinkTotal = 0)
        val route = logs.format(start, 200)!!
        assertTrue(route.message.contains("已记录路由（未确认拨号成功）"))
        assertEquals(100L, route.timestamp)
        assertNull(logs.format(start.copy(snapshot = true), 201))
        val end = logs.format(start.copy(closed = true, closedAt = 350, uplinkTotal = 123, downlinkTotal = 456), 500)!!
        assertTrue(end.message.contains("已结束（核心事件）"))
        assertTrue(end.message.contains("上行 123 B；下行 456 B；历时 250 ms"))
        assertTrue(end.message.contains("结束原因：核心连接 API 未提供"))
        assertEquals(350L, end.timestamp)
        val correlation = route.message.lineSequence().first { it.startsWith("会话：") }
        assertTrue(end.message.contains(correlation))
        assertNull(logs.format(start.copy(closed = true, closedAt = 350), 501))
        assertNull(logs.format(start.copy(snapshot = true, closedAt = 350), 502))
        assertNull(logs.format(start, 503))
    }

    @Test fun initialClosedSnapshotIsHistoryAndCannotBeReportedAsNewSuccess() {
        val logs = ConnectionLogTracker()
        val event = ConnectionLogObservation("old", connection(), createdAt = 100, closedAt = 250,
            uplinkTotal = 0, downlinkTotal = 0, snapshot = true)
        val end = logs.format(event, 900)!!
        assertEquals(250L, end.timestamp)
        assertTrue(end.message.contains("已结束（历史快照）"))
        assertTrue(end.message.contains("上行 0 B；下行 0 B"))
        assertFalse(end.message.contains("拨号成功"))
        assertNull(logs.format(event.copy(closed = true, snapshot = false), 901))
    }

    @Test fun activeSnapshotDoesNotInventAStartOrDialSuccess() {
        val message = ConnectionLogTracker().format(ConnectionLogObservation("active", connection(),
            createdAt = 100, uplinkTotal = 200, downlinkTotal = 300, snapshot = true), 900)!!.message
        assertTrue(message.contains("活跃连接快照（未确认拨号成功）"))
        assertFalse(message.contains("已结束"))
        assertFalse(message.contains("已记录路由"))
    }

    @Test fun closeWithoutMetadataKeepsKnownRouteButNeverInventsFinalCountersOrCause() {
        val logs = ConnectionLogTracker()
        logs.format(ConnectionLogObservation("one", connection(), createdAt = 100, uplinkTotal = 100), 100)
        val end = logs.format(ConnectionLogObservation("one", null, closed = true, closedAt = 300), 900)!!
        assertTrue(end.message.contains("example.test"))
        assertTrue(end.message.contains("上行 未知；下行 未知；历时 200 ms"))
        assertFalse(end.message.contains("上行 100"))
        val missing = logs.format(ConnectionLogObservation("missing", null, closed = true), 950)!!
        assertTrue(missing.message.contains("核心未提供（无法补全）"))
        assertTrue(missing.message.contains("历时 未知"))
        assertEquals(950L, missing.timestamp)
    }

    @Test fun boundedHistoryAndSessionResetCannotGrowWithoutLimit() {
        val logs = ConnectionLogTracker(2)
        fun event(id: String) = ConnectionLogObservation(id, connection())
        val first = logs.format(event("one"), 1)!!
        assertNull(logs.format(event("one"), 1))
        logs.format(event("two"), 1)
        logs.format(event("three"), 1)
        assertTrue(logs.format(event("one"), 1) != null)
        assertNull(logs.format(event(""), 1))
        val oldSession = logs.sessionId
        logs.clear()
        assertNotEquals(oldSession, logs.sessionId)
        assertNotEquals(first.message, logs.format(event("one"), 1)!!.message)
    }

    @Test fun staleCallbacksCannotPopulateANewSessionAfterStopAndRestart() {
        val logs = ConnectionLogTracker()
        val oldSession = logs.sessionId
        val event = ConnectionLogObservation("one", connection(), createdAt = 100)
        logs.format(event, 100, oldSession)
        logs.clear()
        assertNull(logs.format(event.copy(closed = true, closedAt = 200), 200, oldSession))
        val newMessage = logs.format(event, 300, logs.sessionId)!!.message
        assertTrue(newMessage.contains(logs.sessionId))
        assertFalse(newMessage.contains(oldSession))
    }

    @Test fun countersAndDurationRemainUnknownForInvalidNativeValues() {
        val message = ConnectionLogTracker().format(ConnectionLogObservation("invalid", connection(),
            createdAt = 300, closedAt = 100, uplinkTotal = -1, downlinkTotal = -3), 500)!!.message
        assertTrue(message.contains("上行 未知；下行 未知；历时 未知"))
    }

    @Test fun correlationSurvivesRedactionAndRuleSummaryIdsDistinguishGroups() {
        val nativeId = "12345678-1234-1234-1234-123456789abc"
        val msg = ConnectionLogTracker().format(ConnectionLogObservation(nativeId, connection()), 1)!!.message
        assertFalse(msg.contains(nativeId))
        assertEquals(msg, com.rr.client.security.SecretRedactor.redact(msg))
        val other = ConnectionRouteLog.format(connection().copy(rule = "package_name=com.tencent.mm"))
        assertNotEquals(msg.lineSequence().first { it.startsWith("规则摘要 ID：") },
            other.lineSequence().first { it.startsWith("规则摘要 ID：") })
    }

    @Test fun sharedAddressHintsOnlyMatchStrictIpv4TargetsAndNeverChangeRouting() {
        listOf("100.64.0.0", "100.64.0.1:443", "100.127.255.255:65535").forEach { target ->
            assertTrue(target, ConnectionRouteLog.isSharedIpv4Target(target))
            val message = ConnectionRouteLog.format(connection().copy(destination = target))
            assertTrue(message.contains("共享地址目标；可能为旧映射或运营商地址，尚未确认"))
            assertTrue(message.contains("直连 (direct / direct)"))
        }
        listOf("39.100.97.3", "100.63.255.255:443", "100.128.0.0:443", "100.64.256.1",
            "100.64.1.2.example.test:443", "[::ffff:100.64.1.2]:443", "100.64.01.2:443",
            "100.64.1.2:65536", "100.64.1.2:", "100.64.1.2:abc", "100.64.1.2:443:1").forEach { target ->
            assertFalse(target, ConnectionRouteLog.isSharedIpv4Target(target))
        }
    }

    @Test fun coreDiagnosticsKeepOnlyWarningsAndErrorsWithSecretsRedacted() {
        (-2..8).filter { it !in 0..3 }.forEach { level ->
            assertNull(CoreDiagnosticLog.format(level, "ordinary info", "session"))
        }
        (0..3).forEach { level ->
            val message = CoreDiagnosticLog.format(level,
                "dial failed: timeout password=private-value https://example.test/secret-token", "session")!!
            assertTrue(message.contains("dial failed: timeout"))
            assertTrue(message.contains("会话：session"))
            assertTrue(message.contains("时间为接收时间"))
            assertFalse(message.contains("private-value"))
            assertFalse(message.contains("secret-token"))
        }
        assertNull(CoreDiagnosticLog.format(2, "  ", "session"))
    }

    @Test fun ipv6EndpointIsPreserved() {
        assertEquals("[2001:db8::1]:443", ConnectionRouteLog.endpointOnly("[2001:db8::1]:443"))
    }
}

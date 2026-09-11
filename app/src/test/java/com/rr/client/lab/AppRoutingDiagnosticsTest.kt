package com.rr.client.lab

import com.rr.client.core.HevConfigAdapter
import com.rr.client.routing.AppNodeRouting
import org.junit.Assert.*
import org.junit.Test

class AppRoutingDiagnosticsTest {
    private val secondary = "subscription:password=private-secret-node-identity"
    private val tag = AppNodeRouting.nodeTag(secondary)
    private fun config(level: String = "info") = """{
        "log":{"level":"$level"},
        "inbounds":[{"type":"tun","stack":"system","include_package":["com.one","com.two","com.blocked","com.conditional"]}],
        "outbounds":[{"tag":"proxy","type":"vless"},{"tag":"$tag","type":"vmess","password":"never-log-this"}],
        "route":{"rules":[
            {"package_name":["com.one","com.two"],"action":"route","outbound":"$tag"},
            {"package_name":["com.blocked"],"action":"reject"},
            {"package_name":["com.conditional"],"domain_suffix":["example.test"],"action":"route","outbound":"proxy"},
            ${AppNodeRouting.unknownOwnerGuard()}
        ]}
    }"""

    @Test fun snapshotContainsOnlyActualUnconditionalCapturedBindingsAndSafeNodeKeys() {
        for (engine in listOf("SYSTEM", "ROOT")) {
            val snapshot = AppRoutingDiagnostics.snapshot(engine, 17, "private-main-node", config())!!
            assertEquals(engine, snapshot.engine)
            assertEquals(17L, snapshot.generation)
            assertEquals(setOf("com.one", "com.two", "com.blocked"), snapshot.bindings.keys)
            assertEquals(AppRoutingDiagnostics.nodeKey(secondary), snapshot.bindings["com.one"])
            assertEquals("reject", snapshot.bindings["com.blocked"])
            val text = snapshot.loadedMessages().joinToString("\n")
            assertTrue(text.contains("仅选中（4 个）"))
            assertTrue(text.contains("实际绑定配置摘要="))
            assertTrue(text.contains("目标节点缺失或不可用"))
            assertTrue(text.contains("身份不明连接=阻断"))
            listOf(tag, secondary, "never-log-this", "private-main-node", "com.conditional").forEach {
                assertFalse(text, text.contains(it))
            }
        }
    }

    @Test fun hevUsesRealAdaptedEntranceAndPreservesAllSharedOutletCandidates() {
        val canonical = config()
        val adapted = HevConfigAdapter.adapt(canonical)
        val snapshot = AppRoutingDiagnostics.snapshot("HEV", 21, "main", canonical,
            adapted.configJson, adapted.appRouting.packagePorts)!!
        val entrance = snapshot.entrancePackages.entries.single { "com.one" in it.value }.key
        assertEquals(listOf("com.one", "com.two"), snapshot.entrancePackages[entrance])
        val logs = ConnectionLogTracker()
        logs.sessionStartedMessage(adapted.configJson, false, HevConfigAdapter.SOCKS_TAG, snapshot)
        val record = ConnectionRouteRecord("RRBOX", listOf("com.rr.client"), 10427,
            "example.test", "203.0.113.1:443", "tcp", tag, "vmess", "inbound=$entrance => $tag", true, entrance)
        val text = logs.format(ConnectionLogObservation("one", record), 1)!!.message
        assertTrue(text.contains("绑定应用候选：com.one, com.two"))
        assertTrue(text.contains("未确认实际进程"))
        assertTrue(text.contains("运行代次=21"))
        assertTrue(text.contains(AppRoutingDiagnostics.nodeKey(secondary)))
        listOf("RRBOX", "com.rr.client", "10427", tag, secondary).forEach { assertFalse(text, text.contains(it)) }
        val unknown = ConnectionRouteLog.format(record.copy(inbound = "hev-app-in-999"),
            ConnectionLogRuntime.fromConfig(adapted.configJson, false, HevConfigAdapter.SOCKS_TAG, snapshot))
        assertFalse(unknown.contains("绑定应用候选"))
    }

    @Test fun snapshotsStayIndependentAcrossReconfigurationAndMissingEffectiveOutlets() {
        val old = AppRoutingDiagnostics.snapshot("SYSTEM", 1, "main", config())!!
        val changed = config().replace(tag, AppNodeRouting.nodeTag("new-node"))
        val new = AppRoutingDiagnostics.snapshot("ROOT", 2, "main", changed)!!
        assertNotEquals(old.bindingVersion, new.bindingVersion)
        assertEquals(AppRoutingDiagnostics.nodeKey(secondary), old.bindings["com.one"])
        assertEquals(AppRoutingDiagnostics.nodeKey("new-node"), new.bindings["com.one"])
        val noOutlet = AppRoutingDiagnostics.snapshot("ROOT", 3, "main", config(), """{"outbounds":[]}""")!!
        assertTrue(noOutlet.loadedMessages().any { it.contains("未确认出口加载") })
    }

    @Test fun lightweightModeKeepsPolicySnapshotButNeverEnablesDetailedCollection() {
        val snapshot = AppRoutingDiagnostics.snapshot("SYSTEM", 1, "main", config("warn"))!!
        assertFalse(snapshot.detailEnabled)
        assertTrue(snapshot.loadedMessages().first().contains("逐连接采集=关闭"))
        assertEquals(3, snapshot.bindings.size)
        assertFalse(ConnectionRouteLog.enabledForConfig(config("warn")))
        assertNull(AppRoutingDiagnostics.snapshot("SYSTEM", 1, "main", "malformed"))
    }

    @Test fun internalTagsAndCredentialsCannotLeakThroughErrorsOrConnectionRules() {
        val text = AppRoutingDiagnostics.safeText("dial $tag failed password=private-password")
        assertTrue(text.contains(AppRoutingDiagnostics.nodeKey(secondary)))
        assertFalse(text.contains(tag))
        assertFalse(text.contains("private-password"))
        assertEquals(AppRoutingDiagnostics.nodeKey(secondary), AppRoutingDiagnostics.nodeKey(secondary))
        assertNotEquals(AppRoutingDiagnostics.nodeKey(secondary), AppRoutingDiagnostics.nodeKey("different"))
        assertFalse(AppRoutingDiagnostics.safeText("rr-app-node-invalid_").contains("rr-app-node-"))
    }
}

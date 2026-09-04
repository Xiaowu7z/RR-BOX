package com.rr.client.core

import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTrafficConfigAdapterTest {
    private fun node() = ProxyNode(
        id = "benchmark-helper-test",
        tag = "Reality",
        type = ProtocolType.VLESS_REALITY,
        server = "192.0.2.10",
        serverPort = 443,
        uuidOrPassword = "00000000-0000-4000-8000-000000000000",
        flow = "xtls-rprx-vision",
        realityPublicKey = "test-public-key",
        realityShortId = "0123456789abcdef",
        sni = "www.example.com",
        tlsEnabled = true
    )

    private fun stable(mode: String, packages: Set<String> = emptySet()): String {
        val node = node()
        return ConfigBuilder.buildSingBoxConfig(
            selectedNode = node,
            allNodes = listOf(node),
            appRoutes = emptyList(),
            smartRouting = false,
            perAppMode = mode,
            selectedPackages = packages,
            fastForwarding = false
        )
    }

    private fun tun(config: String) = JsonParser.parseString(config)
        .asJsonObject
        .getAsJsonArray("inbounds")[0]
        .asJsonObject

    @Test
    fun allowListTemporarilyAddsDownloadProviderWithoutDroppingExistingApps() {
        val adapted = BenchmarkTrafficConfigAdapter.routePackage(
            stable(
                PerAppPolicyResolver.MODE_ALLOW_LIST,
                setOf("com.android.chrome")
            ),
            "com.android.providers.downloads"
        )
        val include = tun(adapted).getAsJsonArray("include_package").map { it.asString }

        assertTrue(include.contains("com.android.chrome"))
        assertTrue(include.contains("com.rr.client"))
        assertTrue(include.contains("com.android.providers.downloads"))
    }

    @Test
    fun bypassListTemporarilyRemovesDownloadProvider() {
        val adapted = BenchmarkTrafficConfigAdapter.routePackage(
            stable(
                PerAppPolicyResolver.MODE_DISALLOW_LIST,
                setOf("com.android.providers.downloads", "com.example.direct")
            ),
            "com.android.providers.downloads"
        )
        val exclude = tun(adapted).getAsJsonArray("exclude_package").map { it.asString }

        assertFalse(exclude.contains("com.android.providers.downloads"))
        assertTrue(exclude.contains("com.example.direct"))
    }

    @Test
    fun allModeRemainsUnrestricted() {
        val adapted = BenchmarkTrafficConfigAdapter.routePackage(
            stable(PerAppPolicyResolver.MODE_ALL),
            "com.android.providers.downloads"
        )
        val tun = tun(adapted)

        assertFalse(tun.has("include_package"))
        assertFalse(tun.has("exclude_package"))
    }
}

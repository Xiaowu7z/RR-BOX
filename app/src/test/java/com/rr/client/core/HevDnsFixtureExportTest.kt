package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.vpn.HevTunnelConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Feed real production configurations to the native DNS transport gate, without external DNS. */
class HevDnsFixtureExportTest {
    @Test
    fun exportEveryDnsSmartAndFastCombination() {
        val appDirectory = if (File("src/main/AndroidManifest.xml").isFile) File(".") else File("app")
        val output = File(appDirectory, "build/hev-dns-fixtures").apply { mkdirs() }
        val node = ProxyNode(
            id = "hev-dns-validation", tag = "Local verification fixture", type = ProtocolType.SOCKS,
            server = "192.0.2.1", serverPort = 1080,
            rawJson = """{"type":"socks","server":"192.0.2.1","server_port":1080,"version":"5"}"""
        )
        val variants = JsonArray()
        for (smart in listOf(false, true)) {
            for (dns in listOf(false, true)) {
                for (fast in listOf(false, true)) {
                    val filename = "hev-smart-$smart-dns-$dns-fast-$fast.json"
                    val stable = ConfigBuilder.buildSingBoxConfig(
                        selectedNode = node, allNodes = listOf(node), appRoutes = emptyList(),
                        smartRouting = smart, enableDnsRules = dns, fastForwarding = fast
                    )
                    File(output, filename).writeText(HevConfigAdapter.adapt(stable).configJson)
                    variants.add(JsonObject().apply {
                        addProperty("file", filename)
                        addProperty("smart", smart)
                        addProperty("dns", dns)
                        addProperty("fast", fast)
                    })
                }
            }
        }
        File(output, "hev.yaml").writeText(HevTunnelConfig.build(HevConfigAdapter.SOCKS_PORT))
        assertEquals(8, variants.size())
        File(output, "manifest.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("schema", 1)
                addProperty("producer", "ConfigBuilder + HevConfigAdapter + HevTunnelConfig")
                addProperty("dns_address", HevTunnelConfig.DNS_ADDRESS)
                add("variants", variants)
            }
        ))
    }
}

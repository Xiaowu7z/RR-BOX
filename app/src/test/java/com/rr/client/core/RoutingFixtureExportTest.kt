package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Export the actual production builder output for the separate native-core gate. */
class RoutingFixtureExportTest {
    @Test
    fun exportProductionRoutingConfigurations() {
        // Gradle unit tests normally run in app/. Also allow repository-root JVM runners.
        val appDirectory = if (File("src/main/AndroidManifest.xml").isFile) File(".") else File("app")
        val output = File(appDirectory, "build/routing-fixtures").apply { mkdirs() }
        val assets = File(appDirectory, "src/main/assets/rules").absoluteFile
        val node = ProxyNode(
            id = "routing-validation",
            tag = "Local verification fixture",
            type = ProtocolType.SOCKS,
            server = "rr-bootstrap.invalid",
            serverPort = 1080,
            rawJson = """{"type":"socks","server":"rr-bootstrap.invalid","server_port":1080,"version":"5"}"""
        )
        val variants = JsonArray()
        for (rulesMode in listOf("fallback", "bundled", "off")) {
            val paths = if (rulesMode == "bundled") ChinaRuleSetManager.Paths(
                File(assets, "geosite-geolocation-cn.srs").path,
                File(assets, "geoip-cn.srs").path
            ) else null
            val stable = ConfigBuilder.buildSingBoxConfig(
                selectedNode = node,
                allNodes = listOf(node),
                appRoutes = emptyList(),
                smartRouting = rulesMode != "off",
                ruleSets = paths
            )
            for (engine in listOf("system", "hev")) {
                val filename = "$engine-$rulesMode.json"
                File(output, filename).writeText(if (engine == "hev") {
                    HevConfigAdapter.adapt(stable).configJson
                } else stable)
                variants.add(JsonObject().apply {
                    addProperty("file", filename)
                    addProperty("engine", engine)
                    addProperty("rules", rulesMode)
                    addProperty("smart", rulesMode != "off")
                })
            }
        }
        assertEquals(6, variants.size())
        File(output, "manifest.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("schema", 1)
                addProperty("producer", "ConfigBuilder + HevConfigAdapter")
                add("variants", variants)
            }
        ))
    }
}

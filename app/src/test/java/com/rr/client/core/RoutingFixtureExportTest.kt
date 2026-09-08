package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.RoutingPolicySnapshot
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
        // The maintained JSON is canonical. Never let native checks silently exercise
        // the older compiled emergency baseline after a data-only policy update.
        val policyBytes = File(assets, "rrbox-policy.json").readBytes()
        val policy = RoutingPolicySnapshot.parse(policyBytes)
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
                ruleSets = paths,
                routingPolicy = policy
            )
            for (engine in listOf("system", "hev", "root")) {
                val filename = "$engine-$rulesMode.json"
                File(output, filename).writeText(when (engine) {
                    "hev" -> HevConfigAdapter.adapt(stable).configJson
                    "root" -> RootConfigAdapter.adapt(stable).configJson
                    else -> stable
                })
                variants.add(JsonObject().apply {
                    addProperty("file", filename)
                    addProperty("engine", engine)
                    addProperty("rules", rulesMode)
                    addProperty("smart", rulesMode != "off")
                })
            }
        }
        assertEquals(9, variants.size())
        File(output, "manifest.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("schema", 1)
                addProperty("producer", "ConfigBuilder + HevConfigAdapter + RootConfigAdapter")
                addProperty("policy_rule_version", policy.ruleVersion)
                addProperty("policy_sha256", java.security.MessageDigest.getInstance("SHA-256")
                    .digest(policyBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
                add("variants", variants)
            }
        ))
    }
}

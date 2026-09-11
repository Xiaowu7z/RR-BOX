package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.RoutingPolicySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Production JSON consumed by the pinned-core multi-outlet/owner verification gate. */
class AppNodeRoutingFixtureExportTest {
    private fun node(id: String) = ProxyNode(
        id = id, tag = id, type = ProtocolType.SOCKS,
        server = "$id-bootstrap.invalid", serverPort = 1080,
        rawJson = """{"type":"socks","server":"$id-bootstrap.invalid","server_port":1080,"version":"5"}"""
    )

    @Test
    fun exportProductionAppOutletConfigurations() {
        val appDirectory = if (File("src/main/AndroidManifest.xml").isFile) File(".") else File("app")
        val output = File(appDirectory, "build/app-node-fixtures").apply { mkdirs() }
        val policy = RoutingPolicySnapshot.parse(File(appDirectory, "src/main/assets/rules/rrbox-policy.json").readBytes())
        val main = node("la")
        val extra = node("hk")
        val variants = JsonArray()
        for (smart in listOf(false, true)) {
            for (available in listOf(true, false)) {
                val stable = ConfigBuilder.buildSingBoxConfig(
                    selectedNode = main,
                    allNodes = if (available) listOf(main, extra) else listOf(main),
                    appRoutes = emptyList(), smartRouting = smart,
                    perAppMode = "ALLOW_LIST",
                    selectedPackages = setOf("org.telegram.messenger", "com.android.chrome"),
                    routingPolicy = policy,
                    appNodeBindings = listOf(AppNodeBinding("org.telegram.messenger", "hk"))
                )
                for (engine in listOf("system", "root", "hev")) {
                    val filename = "$engine-${if (smart) "on" else "off"}-${if (available) "available" else "missing"}.json"
                    File(output, filename).writeText(when (engine) {
                        "root" -> RootConfigAdapter.adapt(stable).configJson
                        "hev" -> HevConfigAdapter.adapt(stable).configJson
                        else -> stable
                    })
                    variants.add(JsonObject().apply {
                        addProperty("file", filename)
                        addProperty("engine", engine)
                        addProperty("smart", smart)
                        addProperty("secondary_available", available)
                    })
                }
            }
        }
        assertEquals(12, variants.size())
        File(output, "manifest.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(
            JsonObject().apply {
                addProperty("schema", 1)
                addProperty("producer", "ConfigBuilder + HevConfigAdapter + RootConfigAdapter")
                addProperty("main_node_id", "la")
                addProperty("secondary_node_id", "hk")
                addProperty("bound_package", "org.telegram.messenger")
                addProperty("ordinary_package", "com.android.chrome")
                add("variants", variants)
            }
        ))
    }
}

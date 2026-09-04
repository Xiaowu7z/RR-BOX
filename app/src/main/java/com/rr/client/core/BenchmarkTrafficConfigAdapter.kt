package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * Benchmark-only adapter used by Network Lab.
 *
 * It temporarily makes one helper package participate in the VPN without changing the persisted
 * canonical configuration. Normal RRBOX routing is restored by rebuilding from the untouched
 * canonical config after the benchmark finishes.
 */
object BenchmarkTrafficConfigAdapter {
    private const val SELF_PACKAGE = "com.rr.client"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun routePackage(stableConfigJson: String, packageName: String): String {
        val helper = packageName.trim()
        require(helper.isNotEmpty()) { "测速 helper 包名为空" }
        require(helper != SELF_PACKAGE) { "测速 helper 不能使用 RRBOX 自身 UID" }

        val root = JsonParser.parseString(stableConfigJson).asJsonObject.deepCopy()
        val inbounds = root.getAsJsonArray("inbounds")
            ?: throw IllegalArgumentException("稳定配置缺少 inbounds")
        val tun = inbounds.firstOrNull { element ->
            element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
        }?.asJsonObject ?: throw IllegalArgumentException("稳定配置缺少 TUN inbound")

        val include = tun.getAsJsonArray("include_package")
        val exclude = tun.getAsJsonArray("exclude_package")
        require(include == null || exclude == null) {
            "稳定配置同时包含 include_package 与 exclude_package"
        }

        when {
            include != null -> {
                val existing = include.mapNotNull {
                    it.takeIf { value -> value.isJsonPrimitive }?.asString
                }.toSet()
                if (helper !in existing) include.add(helper)
            }

            exclude != null -> {
                val filtered = JsonArray()
                exclude.forEach { value ->
                    val name = value.takeIf { it.isJsonPrimitive }?.asString
                    if (name != helper) filtered.add(value.deepCopy())
                }
                if (filtered.size() == 0) tun.remove("exclude_package")
                else tun.add("exclude_package", filtered)
            }

            else -> Unit // all-app mode already includes the helper UID.
        }

        return gson.toJson(root)
    }
}

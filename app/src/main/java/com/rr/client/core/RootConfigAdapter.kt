package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.routing.ResolvedPerAppPolicy

/** Adapts a canonical config for the native TUN supplied by the Root engine. */
object RootConfigAdapter {
    const val TUN_IPV4_ADDRESS = "172.19.0.1/30"
    const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1/126"
    const val TUN_MTU = 1500

    private const val SELF_PACKAGE = "com.rr.client"
    private const val DIRECT_DNS_ADDRESS = "223.5.5.5"
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class Runtime(
        val configJson: String,
        val perAppPolicy: ResolvedPerAppPolicy
    )

    fun adapt(stableConfigJson: String): Runtime {
        val root = JsonParser.parseString(stableConfigJson).asJsonObject.deepCopy()
        val inbounds = root.getAsJsonArray("inbounds")
            ?: throw IllegalArgumentException("稳定配置缺少 inbounds")
        val tuns = inbounds.filter { element ->
            element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
        }
        require(tuns.size == 1) { "Root 模式需要且仅支持 1 个 TUN inbound" }
        val tun = tuns.single().asJsonObject
        val tunTag = tun.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("稳定配置 TUN inbound 缺少 tag")
        val policy = extractPolicy(tun)

        // The privileged helper owns addresses, UID selection and policy routing. libbox
        // receives its TUN fd and keeps the canonical inbound identity and system stack.
        tun.add("address", JsonArray().apply {
            add(TUN_IPV4_ADDRESS)
            add(TUN_IPV6_ADDRESS)
        })
        tun.addProperty("mtu", TUN_MTU)
        tun.addProperty("stack", "system")
        tun.addProperty("auto_route", false)
        tun.addProperty("strict_route", false)
        tun.remove("include_package")
        tun.remove("exclude_package")

        val route = root.getAsJsonObject("route")
            ?: throw IllegalArgumentException("稳定配置缺少 route")
        val rules = route.getAsJsonArray("rules") ?: JsonArray()
        route.add("rules", JsonArray().apply {
            // Android apps can use any configured resolver, including a private LAN IP.
            // Handle their TCP/UDP DNS before private-address and business routing rules,
            // even when optional protocol sniffing or DNS interception is disabled.
            add(JsonObject().apply {
                add("inbound", JsonArray().apply { add(tunTag) })
                addProperty("port", 53)
                add("network", JsonArray().apply { add("tcp"); add("udp") })
                addProperty("action", "hijack-dns")
            })
            rules.forEach(::add)
        })

        // Older canonical configurations can use Android's local resolver. Its netd
        // requests would re-enter this TUN; use the existing explicit direct upstream
        // instead, keeping server tags and dial options so DNS policy remains intact.
        root.getAsJsonObject("dns")?.getAsJsonArray("servers")?.forEach { element ->
            if (element.isJsonObject) {
                val server = element.asJsonObject
                if (server.get("type")?.asString == "local") {
                    server.addProperty("type", "udp")
                    server.addProperty("server", DIRECT_DNS_ADDRESS)
                    server.addProperty("server_port", 53)
                }
            }
        }

        return Runtime(configJson = gson.toJson(root), perAppPolicy = policy)
    }

    private fun extractPolicy(tun: JsonObject): ResolvedPerAppPolicy {
        val include = readPackages(tun, "include_package")
        val exclude = readPackages(tun, "exclude_package")
        require(include.isEmpty() || exclude.isEmpty()) {
            "稳定配置同时包含 include_package 与 exclude_package"
        }
        // Never turn an explicit allow-list containing only this app (or empty values)
        // into all-app interception. Normal canonical allow-lists always have a client.
        require(!tun.has("include_package") || include.isNotEmpty()) {
            "Root 仅选中代理模式至少需要选择 1 个其他应用"
        }
        return ResolvedPerAppPolicy(allowedPackages = include, disallowedPackages = exclude)
    }

    private fun readPackages(tun: JsonObject, key: String): List<String> {
        val value = tun.get(key) ?: return emptyList()
        require(value.isJsonArray) { "稳定配置 $key 必须是应用包名数组" }
        return value.asJsonArray.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                "稳定配置 $key 包含无效应用包名"
            }
            element.asString.trim()
        }.filter { it.isNotEmpty() && it != SELF_PACKAGE }.distinct().sorted()
    }
}

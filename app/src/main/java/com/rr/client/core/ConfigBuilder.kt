package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.ProtocolType

/**
 * Builds the smallest current-schema sing-box profile needed to prove that the
 * Android VPN and one selected proxy node can carry traffic.
 *
 * Advanced rule-sets and per-app routing are deliberately not injected in this
 * connectivity alpha. They will be layered back after the tunnel is stable.
 */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = false,
        enableDnsRules: Boolean = true
    ): String {
        // Keep the public API stable while intentionally ignoring advanced
        // routing inputs during the connectivity milestone.
        @Suppress("UNUSED_VARIABLE")
        val deferredFeatures = Triple(allNodes, appRoutes, smartRouting && enableDnsRules)

        val proxyOutbound = buildProxyOutbound(selectedNode).apply {
            addProperty("tag", "proxy")
            if (!isIpLiteral(selectedNode.server) && !has("domain_resolver")) {
                addProperty("domain_resolver", "local-dns")
            }
        }

        val root = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "info")
                addProperty("timestamp", true)
            })

            add("dns", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "udp")
                        addProperty("tag", "local-dns")
                        addProperty("server", "223.5.5.5")
                        addProperty("server_port", 53)
                    })
                    add(JsonObject().apply {
                        addProperty("type", "tls")
                        addProperty("tag", "remote-dns")
                        addProperty("server", "1.1.1.1")
                        addProperty("server_port", 853)
                        addProperty("detour", "proxy")
                        add("tls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("server_name", "cloudflare-dns.com")
                        })
                    })
                })
                addProperty("final", "remote-dns")
                addProperty("strategy", "prefer_ipv4")
            })

            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tun")
                    addProperty("tag", "tun-in")
                    add("address", JsonArray().apply {
                        add("172.19.0.1/30")
                        add("fdfe:dcba:9876::1/126")
                    })
                    addProperty("auto_route", true)
                    addProperty("strict_route", true)
                    addProperty("stack", "system")
                })
            })

            add("outbounds", JsonArray().apply {
                add(proxyOutbound)
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })

            add("route", JsonObject().apply {
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("action", "sniff")
                    })
                    add(JsonObject().apply {
                        addProperty("protocol", "dns")
                        addProperty("action", "hijack-dns")
                    })
                    add(JsonObject().apply {
                        addProperty("ip_is_private", true)
                        addProperty("outbound", "direct")
                    })
                })
                addProperty("final", "proxy")
                addProperty("default_domain_resolver", "local-dns")
            })
        }

        return gson.toJson(root)
    }

    /**
     * Preserve a complete RRVPS sing-box client profile verbatim apart from
     * formatting. Validation is performed by Libbox before the VPN is started.
     */
    fun prepareImportedProfile(rawProfile: String): String {
        val root = JsonParser.parseString(rawProfile).asJsonObject
        require(root.getAsJsonArray("outbounds")?.size()?.let { it > 0 } == true) {
            "完整配置缺少 outbounds"
        }
        require(root.getAsJsonArray("inbounds")?.any { element ->
            element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
        } == true) {
            "完整配置缺少 TUN inbound"
        }
        return gson.toJson(root)
    }

    private fun buildProxyOutbound(node: ProxyNode): JsonObject {
        if (node.rawJson.isNotBlank()) {
            return runCatching {
                JsonParser.parseString(node.rawJson).asJsonObject.deepCopy()
            }.getOrElse { error("订阅节点 JSON 无法解析：${it.message}") }
        }

        return when (node.type) {
            ProtocolType.VLESS_REALITY -> JsonObject().apply {
                require(node.server.isNotBlank()) { "VLESS 节点缺少服务器地址" }
                require(node.uuidOrPassword.isNotBlank()) { "VLESS 节点缺少 UUID" }
                require(node.realityPublicKey.isNotBlank()) { "Reality 节点缺少公钥" }

                addProperty("type", "vless")
                addProperty("server", node.server)
                addProperty("server_port", node.serverPort)
                addProperty("uuid", node.uuidOrPassword)
                if (node.flow.isNotBlank()) addProperty("flow", node.flow)
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", node.sni.ifBlank { "www.apple.com" })
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", "chrome")
                    })
                    add("reality", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("public_key", node.realityPublicKey)
                        if (node.realityShortId.isNotBlank()) {
                            addProperty("short_id", node.realityShortId)
                        }
                    })
                })
            }

            ProtocolType.HYSTERIA2 -> JsonObject().apply {
                require(node.server.isNotBlank()) { "Hysteria2 节点缺少服务器地址" }
                require(node.uuidOrPassword.isNotBlank()) { "Hysteria2 节点缺少密码" }

                addProperty("type", "hysteria2")
                addProperty("server", node.server)
                val hoppingPorts = node.hoppingPorts
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                if (hoppingPorts.isEmpty()) {
                    addProperty("server_port", node.serverPort)
                } else {
                    add("server_ports", JsonArray().apply {
                        hoppingPorts.forEach(::add)
                    })
                }
                addProperty("password", node.uuidOrPassword)
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                })
            }

            else -> error(
                "${node.type} 必须从 RRVPS Sing-box JSON 订阅导入完整 outbound，当前手工字段不足"
            )
        }
    }

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

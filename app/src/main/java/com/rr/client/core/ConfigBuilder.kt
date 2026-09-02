package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.ProtocolType

/**
 * Builds a deliberately small sing-box v1.14 configuration.
 *
 * The first runnable milestone is connectivity. Do not reference external
 * rule-sets or legacy special outbounds here: a missing rule-set or a field
 * removed by sing-box 1.14 must never make the VPN process disappear.
 */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Suppress("UNUSED_PARAMETER")
    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true
    ): String {
        require(selectedNode.server.isNotBlank()) { "节点服务器地址为空" }
        require(selectedNode.serverPort in 1..65535) { "节点端口无效: ${selectedNode.serverPort}" }

        val proxyOutbound = buildOutboundJson(selectedNode).apply {
            addProperty("tag", PROXY_TAG)
        }
        val proxyType = proxyOutbound.get("type")?.asString.orEmpty()
        require(proxyType.isNotBlank() && proxyType !in NON_PROXY_TYPES) {
            "所选节点不是可连接的代理出站: ${selectedNode.tag}"
        }

        return gson.toJson(
            JsonObject().apply {
                add("log", JsonObject().apply {
                    addProperty("level", "info")
                    addProperty("timestamp", true)
                })

                // sing-box 1.14 removed the old DNS server address format.
                add("dns", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "udp")
                            addProperty("tag", DNS_TAG)
                            addProperty("server", "223.5.5.5")
                            addProperty("server_port", 53)
                        })
                    })
                    addProperty("final", DNS_TAG)
                    addProperty("strategy", "ipv4_only")
                })

                add("inbounds", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tun")
                        addProperty("tag", "tun-in")
                        add("address", JsonArray().apply { add("172.19.0.1/30") })
                        addProperty("mtu", 1500)
                        addProperty("auto_route", true)
                        addProperty("strict_route", true)
                        addProperty("stack", "system")
                    })
                })

                // Include only the selected real proxy outbound. Pulling every
                // selector/dependency from a subscription makes one valid node
                // depend on unrelated nodes and blocks the first network test.
                add("outbounds", JsonArray().apply {
                    add(proxyOutbound)
                    add(JsonObject().apply {
                        addProperty("type", "direct")
                        addProperty("tag", DIRECT_TAG)
                    })
                })

                add("route", JsonObject().apply {
                    add("rules", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("action", "sniff")
                        })
                        if (enableDnsRules) {
                            add(JsonObject().apply {
                                addProperty("protocol", "dns")
                                addProperty("action", "hijack-dns")
                            })
                        }

                        add(JsonObject().apply {
                            addProperty("ip_is_private", true)
                            addProperty("outbound", DIRECT_TAG)
                        })

                        appRoutes.asSequence()
                            .filter { it.packageName.isNotBlank() }
                            .filter {
                                it.routeMode == "DIRECT" ||
                                    it.routeMode == "BYPASS" ||
                                    it.routeMode == "PROXY_NODE"
                            }
                            .forEach { appRoute ->
                                add(JsonObject().apply {
                                    add("package_name", JsonArray().apply { add(appRoute.packageName) })
                                    addProperty(
                                        "outbound",
                                        if (appRoute.routeMode == "DIRECT" || appRoute.routeMode == "BYPASS") {
                                            DIRECT_TAG
                                        } else {
                                            PROXY_TAG
                                        }
                                    )
                                })
                            }
                    })
                    addProperty("final", PROXY_TAG)
                    addProperty("default_domain_resolver", DNS_TAG)
                    addProperty("auto_detect_interface", true)
                })
            }
        )
    }

    private fun buildOutboundJson(node: ProxyNode): JsonObject {
        if (node.rawJson.isNotBlank()) {
            val parsed = runCatching {
                JsonParser.parseString(node.rawJson).asJsonObject.deepCopy()
            }.getOrElse {
                throw IllegalArgumentException("节点原始配置不是有效 JSON: ${node.tag}", it)
            }
            parsed.addProperty("tag", PROXY_TAG)
            return parsed
        }

        return JsonObject().apply {
            addProperty("tag", PROXY_TAG)
            when (node.type) {
                ProtocolType.VLESS_REALITY -> {
                    require(node.uuidOrPassword.isNotBlank()) { "VLESS UUID 为空" }
                    require(node.realityPublicKey.isNotBlank()) { "Reality 公钥为空" }
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

                ProtocolType.HYSTERIA2 -> {
                    require(node.uuidOrPassword.isNotBlank()) { "Hysteria2 密码为空" }
                    addProperty("type", "hysteria2")
                    addProperty("server", node.server)
                    if (node.hoppingPorts.isBlank()) {
                        addProperty("server_port", node.serverPort)
                    } else {
                        add("server_ports", JsonArray().apply {
                            node.hoppingPorts
                                .split(',')
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .forEach(::add)
                        })
                    }
                    addProperty("password", node.uuidOrPassword)
                    add("tls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("server_name", node.sni.ifBlank { node.server })
                    })
                }

                else -> throw IllegalArgumentException(
                    "${node.type} 必须通过 RRVPS Sing-box JSON 订阅导入，当前节点缺少原始出站配置"
                )
            }
        }
    }

    private const val PROXY_TAG = "proxy"
    private const val DIRECT_TAG = "direct"
    private const val DNS_TAG = "local-dns"
    private val NON_PROXY_TYPES = setOf("direct", "block", "dns", "selector", "urltest")
}

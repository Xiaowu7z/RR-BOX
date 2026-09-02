package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.ProtocolType

object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true
    ): String {
        val root = JsonObject()

        // 1. Log
        val log = JsonObject().apply {
            addProperty("level", "warn")
            addProperty("timestamp", true)
        }
        root.add("log", log)

        // 2. DNS
        val dns = JsonObject().apply {
            val servers = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "dns-remote")
                    addProperty("address", "https://1.1.1.1/dns-query")
                    addProperty("detour", "proxy")
                })
                add(JsonObject().apply {
                    addProperty("tag", "dns-direct")
                    addProperty("address", "223.5.5.5")
                    addProperty("detour", "direct")
                })
                add(JsonObject().apply {
                    addProperty("tag", "dns-block")
                    addProperty("address", "rcode://success")
                })
            }
            add("servers", servers)

            val rules = JsonArray().apply {
                add(JsonObject().apply {
                    val outbound = JsonArray().apply { add("any") }
                    add("outbound", outbound)
                    addProperty("server", "dns-direct")
                })
                if (smartRouting) {
                    add(JsonObject().apply {
                        val geosite = JsonArray().apply { add("geosite-cn") }
                        add("rule_set", geosite)
                        addProperty("server", "dns-direct")
                    })
                }
            }
            add("rules", rules)
            addProperty("strategy", "prefer_ipv4")
        }
        root.add("dns", dns)

        // 3. Inbounds (TUN)
        val inbounds = JsonArray().apply {
            val tun = JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                addProperty("mtu", 9000)
                addProperty("auto_route", true)
                addProperty("strict_route", false)
                addProperty("stack", "mixed")
                addProperty("sniff", true)
                addProperty("sniff_override_destination", false)
                val inet4 = JsonArray().apply { add("172.19.0.1/30") }
                add("inet4_address", inet4)
            }
            add(tun)
        }
        root.add("inbounds", inbounds)

        // 4. Outbounds
        val outbounds = JsonArray().apply {
            // Main selected proxy
            add(buildOutboundJson(selectedNode).apply { addProperty("tag", "proxy") })

            // Additional distinct node outbounds for per-app routing
            allNodes.forEach { node ->
                if (node.id != selectedNode.id) {
                    add(buildOutboundJson(node))
                }
            }

            // Direct
            add(JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", "direct")
            })

            // Block
            add(JsonObject().apply {
                addProperty("type", "block")
                addProperty("tag", "block")
            })

            // DNS Outbound
            add(JsonObject().apply {
                addProperty("type", "dns")
                addProperty("tag", "dns-out")
            })
        }
        root.add("outbounds", outbounds)

        // 5. Route
        val route = JsonObject().apply {
            val rules = JsonArray().apply {
                // DNS hijack
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("outbound", "dns-out")
                })

                // Per-App routing rules
                appRoutes.forEach { appRoute ->
                    when (appRoute.routeMode) {
                        "DIRECT" -> {
                            add(JsonObject().apply {
                                val pkg = JsonArray().apply { add(appRoute.packageName) }
                                add("package_name", pkg)
                                addProperty("outbound", "direct")
                            })
                        }
                        "PROXY_NODE" -> {
                            appRoute.assignedNodeTag?.let { targetTag ->
                                add(JsonObject().apply {
                                    val pkg = JsonArray().apply { add(appRoute.packageName) }
                                    add("package_name", pkg)
                                    addProperty("outbound", targetTag)
                                })
                            }
                        }
                    }
                }

                if (smartRouting) {
                    // Private IPs & LAN
                    add(JsonObject().apply {
                        val ip = JsonArray().apply {
                            add("10.0.0.0/8")
                            add("172.16.0.0/12")
                            add("192.168.0.0/16")
                            add("127.0.0.0/8")
                        }
                        add("ip_cidr", ip)
                        addProperty("outbound", "direct")
                    })

                    // China Rule Sets
                    add(JsonObject().apply {
                        val ruleset = JsonArray().apply { add("geoip-cn"); add("geosite-cn") }
                        add("rule_set", ruleset)
                        addProperty("outbound", "direct")
                    })
                }

                // Default fallback to proxy
                add(JsonObject().apply {
                    addProperty("outbound", "proxy")
                })
            }
            add("rules", rules)
            addProperty("auto_detect_interface", true)
        }
        root.add("route", route)

        return gson.toJson(root)
    }

    private fun buildOutboundJson(node: ProxyNode): JsonObject {
        val obj = JsonObject()
        obj.addProperty("tag", node.tag)

        when (node.type) {
            ProtocolType.VLESS_REALITY -> {
                obj.addProperty("type", "vless")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
                obj.addProperty("uuid", node.uuidOrPassword)
                if (node.flow.isNotEmpty()) obj.addProperty("flow", node.flow)
                obj.add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", if (node.sni.isNotEmpty()) node.sni else "apple.com")
                    addProperty("utls", JsonObject().apply { addProperty("enabled", true); addProperty("fingerprint", "chrome") }.toString())
                    add("reality", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("public_key", node.realityPublicKey)
                        addProperty("short_id", node.realityShortId)
                    })
                })
            }
            ProtocolType.HYSTERIA2 -> {
                obj.addProperty("type", "hysteria2")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
                obj.addProperty("password", node.uuidOrPassword)
                if (node.hoppingPorts.isNotEmpty()) {
                    obj.addProperty("ports", node.hoppingPorts)
                }
                obj.add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
                })
            }
            ProtocolType.TUIC_V5 -> {
                obj.addProperty("type", "tuic")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
                obj.addProperty("uuid", node.uuidOrPassword)
                obj.addProperty("congestion_controller", "bbr")
                obj.add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
                })
            }
            ProtocolType.VMESS_WS_ARGO -> {
                obj.addProperty("type", "vmess")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
                obj.addProperty("uuid", node.uuidOrPassword)
                obj.addProperty("security", "auto")
                obj.add("transport", JsonObject().apply {
                    addProperty("type", "ws")
                    addProperty("path", if (node.path.isNotEmpty()) node.path else "/")
                    add("headers", JsonObject().apply {
                        if (node.host.isNotEmpty()) addProperty("Host", node.host)
                    })
                })
            }
            ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3 -> {
                obj.addProperty("type", "naive")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
                obj.addProperty("username", node.uuidOrPassword.substringBefore(":"))
                obj.addProperty("password", node.uuidOrPassword.substringAfter(":"))
                if (node.type == ProtocolType.NAIVE_H3) {
                    obj.addProperty("quic", true)
                }
            }
            else -> {
                obj.addProperty("type", "socks")
                obj.addProperty("server", node.server)
                obj.addProperty("server_port", node.serverPort)
            }
        }
        return obj
    }
}

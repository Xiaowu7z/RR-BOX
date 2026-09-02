package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
        root.add("log", JsonObject().apply {
            addProperty("level", "warn")
            addProperty("timestamp", true)
        })

        // 2. DNS: Ensure direct DNS can resolve proxy server domain to prevent deadlock
        root.add("dns", JsonObject().apply {
            val servers = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "dns-direct")
                    addProperty("address", "223.5.5.5")
                    addProperty("detour", "direct")
                })
                add(JsonObject().apply {
                    addProperty("tag", "dns-remote")
                    addProperty("address", "https://1.1.1.1/dns-query")
                    addProperty("detour", "proxy")
                })
            }
            add("servers", servers)

            val rules = JsonArray().apply {
                // Outbound server domains MUST be resolved by direct DNS
                add(JsonObject().apply {
                    val domains = JsonArray().apply {
                        allNodes.forEach { if (it.server.isNotEmpty()) add(it.server) }
                    }
                    add("domain", domains)
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
            addProperty("final", "dns-remote")
            addProperty("strategy", "prefer_ipv4")
        })

        // 3. Inbounds: TUN system stack
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                addProperty("mtu", 1500)
                addProperty("auto_route", true)
                addProperty("strict_route", false)
                addProperty("stack", "system")
                addProperty("sniff", true)
                val inet4 = JsonArray().apply { add("172.19.0.1/30") }
                add("inet4_address", inet4)
            })
        })

        // 4. Outbounds: Pass exact node parameters from subscription
        root.add("outbounds", JsonArray().apply {
            // Selected node as the default proxy
            add(buildOutboundJson(selectedNode).apply { addProperty("tag", "proxy") })

            // Other nodes
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
        })

        // 5. Route
        root.add("route", JsonObject().apply {
            val rules = JsonArray().apply {
                // DNS Hijacking
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("outbound", "dns-out")
                })

                // Server domain/IP direct bypass to prevent routing loop
                add(JsonObject().apply {
                    val serverList = JsonArray().apply {
                        allNodes.forEach { if (it.server.isNotEmpty()) add(it.server) }
                    }
                    add("domain", serverList)
                    addProperty("outbound", "direct")
                })

                // Per-App routing
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

                if (smartRouting) {
                    add(JsonObject().apply {
                        val ruleset = JsonArray().apply { add("geoip-cn"); add("geosite-cn") }
                        add("rule_set", ruleset)
                        addProperty("outbound", "direct")
                    })
                }

                // Default
                add(JsonObject().apply {
                    addProperty("outbound", "proxy")
                })
            }
            add("rules", rules)
            addProperty("auto_detect_interface", true)
        })

        return gson.toJson(root)
    }

    private fun buildOutboundJson(node: ProxyNode): JsonObject {
        // If rawJson from RRVPS subscription is available, directly reuse it verbatim!
        if (node.rawJson.isNotBlank()) {
            try {
                val parsed = JsonParser.parseString(node.rawJson).asJsonObject
                parsed.addProperty("tag", node.tag)
                return parsed
            } catch (e: Exception) {
                // fallback
            }
        }

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
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", "chrome")
                    })
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
                    addProperty("insecure", true)
                    if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
                })
            }
            else -> {
                obj.addProperty("type", "direct")
            }
        }
        return obj
    }
}

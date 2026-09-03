package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode

/**
 * Builds a small sing-box 1.14 client profile for the selected node.
 *
 * Connectivity is the first milestone. An unrelated node in the same
 * subscription must never make the selected node fail configuration parsing,
 * so only the selected proxy outbound and the direct outbound are emitted.
 */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val DNS_DIRECT = "dns-direct"
    private const val DNS_REMOTE = "dns-remote"

    private val INTERNAL_OUTBOUND_TYPES = setOf(
        "direct",
        "block",
        "dns",
        "selector",
        "urltest"
    )

    private val DIRECT_DOMAIN_SUFFIXES = listOf(
        ".cn",
        ".baidu.com",
        ".qq.com",
        ".taobao.com",
        ".tmall.com",
        ".jd.com",
        ".alipay.com",
        ".aliyun.com",
        ".tencent.com",
        ".weixin.com",
        ".wechat.com",
        ".bilibili.com",
        ".163.com",
        ".126.com",
        ".zhihu.com"
    )

    @Suppress("UNUSED_PARAMETER")
    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true
    ): String {
        val proxyOutbound = buildOutboundJson(selectedNode)
            ?: throw IllegalArgumentException(
                "节点「${selectedNode.tag}」缺少可用配置，暂时无法连接"
            )

        proxyOutbound.addProperty("tag", TAG_PROXY)
        configureBootstrapResolver(proxyOutbound)

        return gson.toJson(
            JsonObject().apply {
                add("log", JsonObject().apply {
                    addProperty("level", "info")
                    addProperty("timestamp", true)
                })

                add("dns", buildDnsConfig(selectedNode, smartRouting))

                add("inbounds", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "tun")
                        addProperty("tag", "tun-in")
                        add("address", JsonArray().apply {
                            add("172.19.0.1/30")
                        })
                        addProperty("mtu", 1500)
                        addProperty("auto_route", true)
                        addProperty("strict_route", true)
                        addProperty("stack", "system")
                    })
                })

                add("outbounds", JsonArray().apply {
                    add(proxyOutbound)
                    add(JsonObject().apply {
                        addProperty("type", "direct")
                        addProperty("tag", TAG_DIRECT)
                    })
                })

                add("route", JsonObject().apply {
                    add("rules", JsonArray().apply {
                        // Legacy inbound sniff fields were removed before 1.14.
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
                            addProperty("outbound", TAG_DIRECT)
                        })

                        if (smartRouting) {
                            add(JsonObject().apply {
                                add("domain_suffix", JsonArray().apply {
                                    DIRECT_DOMAIN_SUFFIXES.forEach(::add)
                                })
                                addProperty("outbound", TAG_DIRECT)
                            })
                        }

                        // Keep only direct/bypass package rules during the
                        // connectivity milestone. Per-app alternate-node rules
                        // need an explicit outbound dependency graph.
                        appRoutes.asSequence()
                            .filter { it.packageName.isNotBlank() }
                            .filter { it.routeMode == "DIRECT" || it.routeMode == "BYPASS" }
                            .forEach { appRoute ->
                                add(JsonObject().apply {
                                    add("package_name", JsonArray().apply {
                                        add(appRoute.packageName)
                                    })
                                    addProperty("outbound", TAG_DIRECT)
                                })
                            }
                    })
                    addProperty("final", TAG_PROXY)
                    addProperty("default_domain_resolver", DNS_DIRECT)
                    addProperty("auto_detect_interface", true)
                })
            }
        )
    }

    private fun buildDnsConfig(selectedNode: ProxyNode, smartRouting: Boolean): JsonObject =
        JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "udp")
                    addProperty("tag", DNS_DIRECT)
                    addProperty("server", "223.5.5.5")
                    addProperty("server_port", 53)
                    addProperty("detour", TAG_DIRECT)
                })
                add(JsonObject().apply {
                    addProperty("type", "tls")
                    addProperty("tag", DNS_REMOTE)
                    addProperty("server", "1.1.1.1")
                    addProperty("server_port", 853)
                    addProperty("detour", TAG_PROXY)
                    add("tls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("server_name", "cloudflare-dns.com")
                    })
                })
            })

            add("rules", JsonArray().apply {
                if (!isIpLiteral(selectedNode.server)) {
                    add(JsonObject().apply {
                        add("domain", JsonArray().apply { add(selectedNode.server) })
                        addProperty("action", "route")
                        addProperty("server", DNS_DIRECT)
                    })
                }
                if (smartRouting) {
                    add(JsonObject().apply {
                        add("domain_suffix", JsonArray().apply {
                            DIRECT_DOMAIN_SUFFIXES.forEach(::add)
                        })
                        addProperty("action", "route")
                        addProperty("server", DNS_DIRECT)
                    })
                }
            })
            addProperty("final", DNS_REMOTE)
            addProperty("strategy", "prefer_ipv4")
        }

    /** Returns null only when a URI-derived node lacks essential fields. */
    private fun buildOutboundJson(node: ProxyNode): JsonObject? {
        if (node.rawJson.isNotBlank()) {
            return runCatching {
                val parsed = JsonParser.parseString(node.rawJson).asJsonObject.deepCopy()
                parsed.remove("tag")
                normalizeRawOutbound(parsed)
            }.getOrNull()?.takeIf { outbound ->
                val type = primitiveString(outbound.get("type"))
                type.isNotBlank() && type !in INTERNAL_OUTBOUND_TYPES
            }
        }

        val outbound = JsonObject()
        return when (node.type) {
            ProtocolType.VLESS_REALITY,
            ProtocolType.VLESS_TLS -> buildVless(outbound, node)

            ProtocolType.HYSTERIA2 -> buildHysteria2(outbound, node)
            ProtocolType.TUIC_V5 -> buildTuic(outbound, node)
            ProtocolType.VMESS_TLS,
            ProtocolType.VMESS_WS_ARGO -> buildVmess(outbound, node)

            ProtocolType.TROJAN -> buildTrojan(outbound, node)
            ProtocolType.SHADOWSOCKS -> buildShadowsocks(outbound, node)

            // AnyTLS and Naive contain protocol-specific fields that must come
            // from the RRVPS sing-box JSON outbound until dedicated URI
            // parsers are implemented.
            ProtocolType.ANYTLS,
            ProtocolType.NAIVE_H2,
            ProtocolType.NAIVE_H3,
            ProtocolType.CUSTOM -> null
        }
    }

    private fun normalizeRawOutbound(outbound: JsonObject): JsonObject {
        val type = primitiveString(outbound.get("type"))

        // Some URI converters put TLS fields beside the outbound. sing-box
        // 1.14 accepts ALPN only inside the nested tls object.
        val legacyAlpn = outbound.remove("alpn")
        val legacySni = outbound.remove("sni")
        val legacyInsecure = outbound.remove("insecure")
            ?: outbound.remove("allow_insecure")
            ?: outbound.remove("skip_cert_verify")

        if (legacyAlpn != null || legacySni != null || legacyInsecure != null) {
            val tls = ensureTls(outbound)
            if (legacyAlpn != null) {
                val values = toStringArray(legacyAlpn)
                if (values.size() > 0) tls.add("alpn", values)
            }
            val serverName = primitiveString(legacySni)
            if (serverName.isNotBlank() && !tls.has("server_name")) {
                tls.addProperty("server_name", serverName)
            }
            if (legacyInsecure != null && !tls.has("insecure")) {
                tls.addProperty("insecure", primitiveBoolean(legacyInsecure))
            }
        }

        if (type == "hysteria2" || type == "hy2") {
            val legacyPorts = outbound.remove("ports") ?: outbound.remove("mport")
            if (!outbound.has("server_ports") && legacyPorts != null) {
                val ports = toPortArray(legacyPorts)
                if (ports.size() > 0) outbound.add("server_ports", ports)
            }
        }

        // The generated profile contains only proxy and direct. A stale detour
        // to an omitted selector/url-test would make an otherwise valid node
        // impossible to start.
        val detour = primitiveString(outbound.get("detour"))
        if (detour.isNotBlank() && detour != TAG_DIRECT) {
            outbound.remove("detour")
        }

        return outbound
    }

    private fun buildVless(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.uuidOrPassword.isBlank()) return null
        outbound.addProperty("type", "vless")
        outbound.addProperty("server", node.server)
        outbound.addProperty("server_port", node.serverPort)
        outbound.addProperty("uuid", node.uuidOrPassword)
        if (node.flow.isNotBlank()) outbound.addProperty("flow", node.flow)
        addTransport(outbound, node)

        if (node.tlsEnabled) {
            val tls = JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
            }
            if (node.type == ProtocolType.VLESS_REALITY) {
                if (node.realityPublicKey.isBlank()) return null
                tls.add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
                tls.add("reality", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("public_key", node.realityPublicKey)
                    if (node.realityShortId.isNotBlank()) {
                        addProperty("short_id", node.realityShortId)
                    }
                })
            }
            addAlpn(tls, node.alpn)
            outbound.add("tls", tls)
        }
        return outbound
    }

    private fun buildHysteria2(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.uuidOrPassword.isBlank()) return null
        outbound.addProperty("type", "hysteria2")
        outbound.addProperty("server", node.server)

        val serverPorts = parsePortList(node.hoppingPorts)
        if (serverPorts.isEmpty()) {
            outbound.addProperty("server_port", node.serverPort)
        } else {
            outbound.add("server_ports", JsonArray().apply {
                serverPorts.forEach(::add)
            })
        }

        outbound.addProperty("password", node.uuidOrPassword)
        if (node.obfs.isNotBlank()) {
            outbound.add("obfs", JsonObject().apply {
                addProperty("type", node.obfs)
                if (node.obfsPassword.isNotBlank()) {
                    addProperty("password", node.obfsPassword)
                }
            })
        }
        outbound.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
            addProperty("insecure", true)
            addAlpn(this, node.alpn)
        })
        return outbound
    }

    private fun buildTuic(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.uuidOrPassword.isBlank()) return null
        outbound.addProperty("type", "tuic")
        outbound.addProperty("server", node.server)
        outbound.addProperty("server_port", node.serverPort)
        outbound.addProperty("uuid", node.uuidOrPassword)
        if (node.extraPassword.isNotBlank()) {
            outbound.addProperty("password", node.extraPassword)
        }
        outbound.addProperty("congestion_control", "bbr")
        outbound.addProperty("udp_relay_mode", "native")
        outbound.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
            addProperty("insecure", true)
            addAlpn(this, node.alpn)
        })
        return outbound
    }

    private fun buildVmess(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.uuidOrPassword.isBlank()) return null
        outbound.addProperty("type", "vmess")
        outbound.addProperty("server", node.server)
        outbound.addProperty("server_port", node.serverPort)
        outbound.addProperty("uuid", node.uuidOrPassword)
        outbound.addProperty("security", "auto")
        addTransport(outbound, node)
        if (node.tlsEnabled) {
            outbound.add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
                addAlpn(this, node.alpn)
            })
        }
        return outbound
    }

    private fun buildTrojan(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.uuidOrPassword.isBlank()) return null
        outbound.addProperty("type", "trojan")
        outbound.addProperty("server", node.server)
        outbound.addProperty("server_port", node.serverPort)
        outbound.addProperty("password", node.uuidOrPassword)
        addTransport(outbound, node)
        outbound.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
            addAlpn(this, node.alpn)
        })
        return outbound
    }

    private fun buildShadowsocks(outbound: JsonObject, node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.ssMethod.isBlank() || node.uuidOrPassword.isBlank()) {
            return null
        }
        outbound.addProperty("type", "shadowsocks")
        outbound.addProperty("server", node.server)
        outbound.addProperty("server_port", node.serverPort)
        outbound.addProperty("method", node.ssMethod)
        outbound.addProperty("password", node.uuidOrPassword)
        return outbound
    }

    private fun addTransport(outbound: JsonObject, node: ProxyNode) {
        when (node.network.lowercase()) {
            "ws" -> outbound.add("transport", JsonObject().apply {
                addProperty("type", "ws")
                if (node.path.isNotBlank()) addProperty("path", node.path)
                if (node.host.isNotBlank()) {
                    add("headers", JsonObject().apply {
                        addProperty("Host", node.host)
                    })
                }
            })

            "grpc" -> outbound.add("transport", JsonObject().apply {
                addProperty("type", "grpc")
                if (node.path.isNotBlank()) addProperty("service_name", node.path)
            })
        }
    }

    private fun configureBootstrapResolver(outbound: JsonObject) {
        val server = primitiveString(outbound.get("server"))
        if (server.isNotBlank() && !isIpLiteral(server)) {
            outbound.addProperty("domain_resolver", DNS_DIRECT)
        } else {
            outbound.remove("domain_resolver")
        }
    }

    private fun ensureTls(outbound: JsonObject): JsonObject {
        val current = outbound.get("tls")
        val tls = if (current != null && current.isJsonObject) {
            current.asJsonObject
        } else {
            JsonObject().also { outbound.add("tls", it) }
        }
        if (!tls.has("enabled")) tls.addProperty("enabled", true)
        return tls
    }

    private fun addAlpn(tls: JsonObject, raw: String) {
        val values = raw.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (values.isNotEmpty()) {
            tls.add("alpn", JsonArray().apply { values.forEach(::add) })
        }
    }

    private fun toStringArray(element: JsonElement): JsonArray = JsonArray().apply {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { value ->
                val text = primitiveString(value)
                if (text.isNotBlank()) add(text)
            }

            element.isJsonPrimitive -> primitiveString(element)
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach(::add)
        }
    }

    private fun toPortArray(element: JsonElement): JsonArray = JsonArray().apply {
        val source = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { value ->
                primitiveString(value).takeIf(String::isNotBlank)
            }

            element.isJsonPrimitive -> primitiveString(element)
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)

            else -> emptyList()
        }
        source.map(::normalizePortRange).forEach(::add)
    }

    private fun parsePortList(raw: String): List<String> = raw
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::normalizePortRange)

    private fun normalizePortRange(value: String): String {
        val trimmed = value.trim()
        return if ('-' in trimmed && ':' !in trimmed) {
            trimmed.replaceFirst('-', ':')
        } else {
            trimmed
        }
    }

    private fun primitiveString(element: JsonElement?): String =
        if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else if (element != null && element.isJsonPrimitive) {
            element.asJsonPrimitive.toString().trim('"')
        } else {
            ""
        }

    private fun primitiveBoolean(element: JsonElement): Boolean =
        runCatching { element.asBoolean }.getOrDefault(false)

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

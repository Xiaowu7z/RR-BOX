package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver

/** Stable sing-box 1.14 runtime configuration. */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val DNS_DIRECT = "dns-direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val RULE_GEOSITE_CN = "geosite-geolocation-cn"
    private const val RULE_GEOIP_CN = "geoip-cn"

    @Suppress("UNUSED_PARAMETER")
    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true,
        perAppMode: String = PerAppPolicyResolver.MODE_ALL,
        selectedPackages: Set<String> = emptySet(),
        ruleSets: ChinaRuleSetManager.Paths? = null
    ): String {
        val proxy = buildSelectedOutbound(selectedNode)
            ?: throw IllegalArgumentException("节点「${selectedNode.tag}」缺少 sing-box 1.14 可用参数")

        proxy.addProperty("tag", TAG_PROXY)
        configureBootstrapResolver(proxy)

        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "info")
                addProperty("timestamp", true)
            })

            add("dns", buildDnsConfig(selectedNode, smartRouting, ruleSets))

            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tun")
                    addProperty("tag", "tun-in")
                    add("address", JsonArray().apply { add("172.19.0.1/30") })
                    addProperty("mtu", 1500)
                    addProperty("auto_route", true)
                    addProperty("strict_route", true)
                    addProperty("stack", "system")
                    applyPerAppMode(this, perAppMode, selectedPackages)
                })
            })

            // Preserve the known-good 0.1.4/0.1.6 topology: selected proxy + direct only.
            add("outbounds", JsonArray().apply {
                add(proxy)
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", TAG_DIRECT)
                })
            })

            add("route", JsonObject().apply {
                add("rules", JsonArray().apply {
                    add(JsonObject().apply { addProperty("action", "sniff") })
                    if (enableDnsRules) {
                        add(JsonObject().apply {
                            addProperty("protocol", "dns")
                            addProperty("action", "hijack-dns")
                        })
                    }

                    if (smartRouting) {
                        add(JsonObject().apply {
                            addProperty("ip_is_private", true)
                            addProperty("outbound", TAG_DIRECT)
                        })

                        if (ruleSets != null) {
                            add(JsonObject().apply {
                                add("rule_set", JsonArray().apply { add(RULE_GEOSITE_CN) })
                                addProperty("outbound", TAG_DIRECT)
                            })
                            add(JsonObject().apply {
                                add("rule_set", JsonArray().apply { add(RULE_GEOIP_CN) })
                                addProperty("outbound", TAG_DIRECT)
                            })
                        } else {
                            add(JsonObject().apply {
                                add("domain_suffix", JsonArray().apply { add("cn") })
                                addProperty("outbound", TAG_DIRECT)
                            })
                        }
                    }
                })

                if (smartRouting && ruleSets != null) {
                    add("rule_set", JsonArray().apply {
                        addLocalRuleSet(RULE_GEOSITE_CN, ruleSets.geositeChina)
                        addLocalRuleSet(RULE_GEOIP_CN, ruleSets.geoipChina)
                    })
                }

                addProperty("final", TAG_PROXY)
                addProperty("default_domain_resolver", DNS_DIRECT)
                addProperty("auto_detect_interface", true)
            })
        })
    }

    private fun applyPerAppMode(tun: JsonObject, mode: String, packages: Set<String>) {
        val selected = packages.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .toList()

        when (mode) {
            PerAppPolicyResolver.MODE_ALL -> Unit
            PerAppPolicyResolver.MODE_ALLOW_LIST -> {
                require(selected.isNotEmpty()) { "仅选中代理模式至少需要选择 1 个应用" }
                tun.add("include_package", JsonArray().apply { selected.forEach(::add) })
            }
            PerAppPolicyResolver.MODE_DISALLOW_LIST -> {
                if (selected.isNotEmpty()) {
                    tun.add("exclude_package", JsonArray().apply { selected.forEach(::add) })
                }
            }
            else -> throw IllegalArgumentException("未知分应用模式：$mode")
        }
    }

    private fun JsonArray.addLocalRuleSet(tag: String, path: String) {
        add(JsonObject().apply {
            addProperty("type", "local")
            addProperty("tag", tag)
            addProperty("format", "binary")
            addProperty("path", path)
        })
    }

    private fun buildDnsConfig(
        selectedNode: ProxyNode,
        smartRouting: Boolean,
        ruleSets: ChinaRuleSetManager.Paths?
    ): JsonObject = JsonObject().apply {
        add("servers", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "udp")
                addProperty("tag", DNS_DIRECT)
                addProperty("server", "223.5.5.5")
                addProperty("server_port", 53)
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
                if (ruleSets != null) {
                    add(JsonObject().apply {
                        add("rule_set", JsonArray().apply { add(RULE_GEOSITE_CN) })
                        addProperty("action", "route")
                        addProperty("server", DNS_DIRECT)
                    })
                } else {
                    add(JsonObject().apply {
                        add("domain_suffix", JsonArray().apply { add("cn") })
                        addProperty("action", "route")
                        addProperty("server", DNS_DIRECT)
                    })
                }
            }
        })
        addProperty("final", DNS_REMOTE)
        addProperty("strategy", "prefer_ipv4")
    }

    /**
     * RRVPS JSON is already protocol-complete, so preserve it after sanitizing
     * version-specific fields. URI-derived nodes have no raw JSON and use the
     * explicit fallback builders below.
     */
    private fun buildSelectedOutbound(node: ProxyNode): JsonObject? {
        if (node.rawJson.isNotBlank()) {
            val expectedType = when (node.type) {
                ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS -> "vless"
                ProtocolType.VMESS_WS_ARGO, ProtocolType.VMESS_TLS -> "vmess"
                ProtocolType.HYSTERIA2 -> "hysteria2"
                ProtocolType.TUIC_V5 -> "tuic"
                ProtocolType.ANYTLS -> "anytls"
                ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3 -> "naive"
                ProtocolType.TROJAN -> "trojan"
                ProtocolType.SHADOWSOCKS -> "shadowsocks"
                ProtocolType.CUSTOM -> null
            }
            val raw = buildRawOutbound(node, expectedType)
            if (raw != null) return raw
        }

        return when (node.type) {
            ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS -> buildVless(node)
            ProtocolType.HYSTERIA2 -> buildHysteria2(node)
            ProtocolType.TUIC_V5 -> buildTuic(node)
            ProtocolType.VMESS_TLS, ProtocolType.VMESS_WS_ARGO -> buildVmess(node)
            ProtocolType.TROJAN -> buildTrojan(node)
            ProtocolType.SHADOWSOCKS -> buildShadowsocks(node)
            ProtocolType.ANYTLS,
            ProtocolType.NAIVE_H2,
            ProtocolType.NAIVE_H3,
            ProtocolType.CUSTOM -> null
        }
    }

    private fun buildRawOutbound(node: ProxyNode, expectedType: String?): JsonObject? {
        val outbound = runCatching {
            JsonParser.parseString(node.rawJson).asJsonObject.deepCopy()
        }.getOrNull() ?: return null

        outbound.remove("tag")
        normalizeLegacyTlsFields(outbound)
        normalizeLegacyPortFields(outbound)

        val type = primitiveString(outbound.get("type")).lowercase()
        if (expectedType != null && type != expectedType) return null
        if (type.isBlank() || type in INTERNAL_OUTBOUND_TYPES) return null

        if (type == "naive") sanitizeNaiveTls(outbound)

        val detour = primitiveString(outbound.get("detour"))
        if (detour.isNotBlank() && detour != TAG_DIRECT) outbound.remove("detour")
        return outbound
    }

    private fun normalizeLegacyTlsFields(outbound: JsonObject) {
        val legacyAlpn = outbound.remove("alpn")
        val legacySni = outbound.remove("sni")
        val legacyInsecure = outbound.remove("insecure")
            ?: outbound.remove("allow_insecure")
            ?: outbound.remove("allowInsecure")
            ?: outbound.remove("skip_cert_verify")

        if (legacyAlpn == null && legacySni == null && legacyInsecure == null) return

        val tls = ensureTls(outbound)
        if (legacyAlpn != null && !tls.has("alpn")) {
            val alpn = toStringArray(legacyAlpn)
            if (alpn.size() > 0) tls.add("alpn", alpn)
        }
        val sni = primitiveString(legacySni)
        if (sni.isNotBlank() && !tls.has("server_name")) tls.addProperty("server_name", sni)
        if (legacyInsecure != null && !tls.has("insecure")) {
            tls.addProperty("insecure", primitiveBoolean(legacyInsecure))
        }
    }

    private fun normalizeLegacyPortFields(outbound: JsonObject) {
        val type = primitiveString(outbound.get("type")).lowercase()
        if (type != "hysteria2" && type != "hy2") return
        val legacy = outbound.remove("ports") ?: outbound.remove("mport") ?: return
        if (!outbound.has("server_ports")) {
            val ports = toPortArray(legacy)
            if (ports.size() > 0) outbound.add("server_ports", ports)
        }
    }

    private fun sanitizeNaiveTls(outbound: JsonObject) {
        val tls = ensureTls(outbound)
        listOf(
            "insecure", "alpn", "disable_sni", "min_version", "max_version",
            "cipher_suites", "curve_preferences", "client_certificate",
            "client_certificate_path", "client_key", "client_key_path",
            "fragment", "record_fragment", "kernel_tx", "kernel_rx", "utls", "reality"
        ).forEach(tls::remove)
        tls.addProperty("enabled", true)
    }

    private fun buildVless(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        if (node.type == ProtocolType.VLESS_REALITY && node.realityPublicKey.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty("uuid", node.uuidOrPassword)
            if (node.flow.isNotBlank()) addProperty("flow", node.flow)
            addTransport(this, node)
            if (node.tlsEnabled || node.type == ProtocolType.VLESS_REALITY) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                    addAlpn(this, node.alpn)
                    if (node.type == ProtocolType.VLESS_REALITY) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", "chrome")
                        })
                        add("reality", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", node.realityPublicKey)
                            if (node.realityShortId.isNotBlank()) addProperty("short_id", node.realityShortId)
                        })
                    }
                })
            }
        }
    }

    private fun buildHysteria2(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "hysteria2")
            addProperty("server", node.server)
            val ports = parsePortList(node.hoppingPorts)
            if (ports.isEmpty()) addProperty("server_port", node.serverPort)
            else add("server_ports", JsonArray().apply { ports.forEach(::add) })
            addProperty("password", node.uuidOrPassword)
            if (node.obfs.isNotBlank()) {
                add("obfs", JsonObject().apply {
                    addProperty("type", node.obfs)
                    if (node.obfsPassword.isNotBlank()) addProperty("password", node.obfsPassword)
                })
            }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                addProperty("insecure", true)
                addAlpn(this, node.alpn.ifBlank { "h3" })
            })
        }
    }

    private fun buildTuic(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "tuic")
            addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty("uuid", node.uuidOrPassword)
            if (node.extraPassword.isNotBlank()) addProperty("password", node.extraPassword)
            addProperty("congestion_control", "bbr")
            addProperty("zero_rtt_handshake", true)
            addProperty("udp_relay_mode", "native")
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                addProperty("insecure", true)
                addAlpn(this, node.alpn.ifBlank { "h3" })
            })
        }
    }

    private fun buildVmess(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty("uuid", node.uuidOrPassword)
            addProperty("security", "auto")
            addTransport(this, node)
            if (node.tlsEnabled) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", "chrome")
                    })
                    addAlpn(this, node.alpn)
                })
            }
        }
    }

    private fun buildTrojan(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty("password", node.uuidOrPassword)
            addTransport(this, node)
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                addAlpn(this, node.alpn)
            })
        }
    }

    private fun buildShadowsocks(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.ssMethod.isBlank() || node.uuidOrPassword.isBlank()) return null
        return JsonObject().apply {
            addProperty("type", "shadowsocks")
            addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty("method", node.ssMethod)
            addProperty("password", node.uuidOrPassword)
        }
    }

    private fun addTransport(outbound: JsonObject, node: ProxyNode) {
        when (node.network.lowercase()) {
            "ws" -> outbound.add("transport", JsonObject().apply {
                addProperty("type", "ws")
                if (node.path.isNotBlank()) addProperty("path", node.path)
                if (node.host.isNotBlank()) {
                    add("headers", JsonObject().apply { addProperty("Host", node.host) })
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
        if (server.isNotBlank() && !isIpLiteral(server)) outbound.addProperty("domain_resolver", DNS_DIRECT)
        else outbound.remove("domain_resolver")
    }

    private fun ensureTls(outbound: JsonObject): JsonObject {
        val existing = outbound.get("tls")
        val tls = if (existing != null && existing.isJsonObject) existing.asJsonObject
        else JsonObject().also { outbound.add("tls", it) }
        if (!tls.has("enabled")) tls.addProperty("enabled", true)
        return tls
    }

    private fun addAlpn(tls: JsonObject, raw: String) {
        val values = raw.split(',').map(String::trim).filter(String::isNotEmpty)
        if (values.isNotEmpty()) tls.add("alpn", JsonArray().apply { values.forEach(::add) })
    }

    private fun toStringArray(element: JsonElement): JsonArray = JsonArray().apply {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { value ->
                val text = primitiveString(value)
                if (text.isNotBlank()) add(text)
            }
            element.isJsonPrimitive -> primitiveString(element).split(',')
                .map(String::trim).filter(String::isNotEmpty).forEach(::add)
        }
    }

    private fun toPortArray(element: JsonElement): JsonArray = JsonArray().apply {
        val source = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { value ->
                primitiveString(value).takeIf(String::isNotBlank)
            }
            element.isJsonPrimitive -> primitiveString(element).split(',')
                .map(String::trim).filter(String::isNotEmpty)
            else -> emptyList()
        }
        source.map(::normalizePortRange).forEach(::add)
    }

    private fun parsePortList(raw: String): List<String> = raw.split(',')
        .map(String::trim).filter(String::isNotEmpty).map(::normalizePortRange)

    private fun normalizePortRange(value: String): String {
        val trimmed = value.trim()
        return if ('-' in trimmed && ':' !in trimmed) trimmed.replaceFirst('-', ':') else trimmed
    }

    private fun primitiveString(element: JsonElement?): String =
        if (element != null && element.isJsonPrimitive) runCatching { element.asString }.getOrDefault("") else ""

    private fun primitiveBoolean(element: JsonElement): Boolean = runCatching { element.asBoolean }.getOrDefault(false)

    private fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    private val INTERNAL_OUTBOUND_TYPES = setOf(
        "direct", "block", "dns", "selector", "urltest", "http", "socks", "wireguard", "ssh"
    )
}

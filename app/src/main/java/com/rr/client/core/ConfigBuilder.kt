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
 * Connectivity-first sing-box 1.14 config builder.
 *
 * Only the selected proxy and a direct outbound are emitted. Known protocols
 * are rebuilt from parsed fields instead of blindly replaying subscription JSON,
 * so stale converter fields cannot poison an otherwise valid node.
 */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val DNS_DIRECT = "dns-direct"
    private const val DNS_REMOTE = "dns-remote"

    @Suppress("UNUSED_PARAMETER")
    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true
    ): String {
        val proxyOutbound = buildSelectedOutbound(selectedNode)
            ?: throw IllegalArgumentException(
                "节点「${selectedNode.tag}」缺少 sing-box 1.14 可用参数"
            )

        proxyOutbound.addProperty("tag", TAG_PROXY)
        configureBootstrapResolver(proxyOutbound)

        return gson.toJson(
            JsonObject().apply {
                add("log", JsonObject().apply {
                    addProperty("level", "info")
                    addProperty("timestamp", true)
                })

                add("dns", buildDnsConfig(selectedNode))

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

                add("outbounds", JsonArray().apply {
                    add(proxyOutbound)
                    add(JsonObject().apply {
                        addProperty("type", "direct")
                        addProperty("tag", TAG_DIRECT)
                    })
                })

                // First milestone: make one real node carry traffic reliably.
                // Smart CN rules and per-app routing stay out of the runtime
                // config until the minimal tunnel passes real-device testing.
                add("route", JsonObject().apply {
                    add("rules", JsonArray().apply {
                        add(JsonObject().apply { addProperty("action", "sniff") })
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
                    })
                    addProperty("final", TAG_PROXY)
                    addProperty("default_domain_resolver", DNS_DIRECT)
                    // On Android this causes libbox's dialer to install the
                    // platform ProtectFunc, which calls VpnService.protect(fd)
                    // and prevents proxy/DNS sockets from looping into TUN.
                    addProperty("auto_detect_interface", true)
                })
            }
        )
    }

    private fun buildDnsConfig(selectedNode: ProxyNode): JsonObject = JsonObject().apply {
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
        })
        addProperty("final", DNS_REMOTE)
        addProperty("strategy", "prefer_ipv4")
    }

    private fun buildSelectedOutbound(node: ProxyNode): JsonObject? = when (node.type) {
        ProtocolType.VLESS_REALITY,
        ProtocolType.VLESS_TLS -> buildVless(node)
        ProtocolType.HYSTERIA2 -> buildHysteria2(node)
        ProtocolType.TUIC_V5 -> buildTuic(node)
        ProtocolType.VMESS_TLS,
        ProtocolType.VMESS_WS_ARGO -> buildVmess(node)
        ProtocolType.TROJAN -> buildTrojan(node)
        ProtocolType.SHADOWSOCKS -> buildShadowsocks(node)
        ProtocolType.ANYTLS -> buildRawOutbound(node, "anytls")
        ProtocolType.NAIVE_H2,
        ProtocolType.NAIVE_H3 -> buildRawOutbound(node, "naive")
        ProtocolType.CUSTOM -> buildRawOutbound(node, null)
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
            val serverPorts = parsePortList(node.hoppingPorts)
            if (serverPorts.isEmpty()) addProperty("server_port", node.serverPort)
            else add("server_ports", JsonArray().apply { serverPorts.forEach(::add) })
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

    private fun buildRawOutbound(node: ProxyNode, expectedType: String?): JsonObject? {
        if (node.rawJson.isBlank()) return null
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
            val values = toStringArray(legacyAlpn)
            if (values.size() > 0) tls.add("alpn", values)
        }
        val serverName = primitiveString(legacySni)
        if (serverName.isNotBlank() && !tls.has("server_name")) tls.addProperty("server_name", serverName)
        if (legacyInsecure != null && !tls.has("insecure")) tls.addProperty("insecure", primitiveBoolean(legacyInsecure))
    }

    private fun normalizeLegacyPortFields(outbound: JsonObject) {
        val type = primitiveString(outbound.get("type")).lowercase()
        if (type != "hysteria2" && type != "hy2") return
        val legacyPorts = outbound.remove("ports") ?: outbound.remove("mport") ?: return
        if (!outbound.has("server_ports")) {
            val ports = toPortArray(legacyPorts)
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
        val current = outbound.get("tls")
        val tls = if (current != null && current.isJsonObject) current.asJsonObject
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

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
import com.rr.client.routing.DomesticRoutingPolicy
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.RoutingPolicySnapshot
import com.rr.client.routing.WeChatIpv6RecoveryPolicy
import com.rr.client.routing.XDestinationRecoveryPolicy

/** Stable sing-box 1.14 runtime configuration. */
object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val DNS_DIRECT = "dns-direct"
    private const val DNS_REMOTE = "dns-remote"
    private const val RULE_GEOSITE_CN = "geosite-geolocation-cn"
    private const val RULE_GEOIP_CN = "geoip-cn"
    private const val SELF_PACKAGE = "com.rr.client"

    @Suppress("UNUSED_PARAMETER")
    fun buildSingBoxConfig(
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        appRoutes: List<AppRouteConfig>,
        smartRouting: Boolean = true,
        enableDnsRules: Boolean = true,
        perAppMode: String = PerAppPolicyResolver.MODE_ALL,
        selectedPackages: Set<String> = emptySet(),
        fastForwarding: Boolean = false,
        ruleSets: ChinaRuleSetManager.Paths? = null,
        routingPolicy: RoutingPolicySnapshot = RoutingPolicySnapshot.bundled()
    ): String {
        val proxy = buildSelectedOutbound(selectedNode)
            ?: throw IllegalArgumentException("节点「${selectedNode.tag}」缺少 sing-box 1.14 可用参数")

        proxy.addProperty("tag", TAG_PROXY)
        configureBootstrapResolver(proxy)

        return gson.toJson(JsonObject().apply {
            add("log", JsonObject().apply {
                // Fast mode never changes the TUN engine/outbound path. It only cuts
                // optional observability work so the known-good data plane is preserved.
                addProperty("level", if (fastForwarding) "warn" else "info")
                addProperty("timestamp", true)
            })

            add("dns", buildDnsConfig(selectedNode, smartRouting, ruleSets, routingPolicy))

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

            // Preserve the known-good 0.1.8 topology: selected proxy + direct only.
            add("outbounds", JsonArray().apply {
                add(proxy)
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", TAG_DIRECT)
                    // HEV submits mapped destinations as domains: resolve a direct business
                    // using the same local resolver chosen by its DNS policy.
                    add("domain_resolver", JsonObject().apply {
                        addProperty("server", DNS_DIRECT)
                        // A recovered IPv6 literal becomes this exact business domain.
                        // Prefer a working IPv4 path without removing IPv6-only answers
                        // or the dialer's normal fallback when IPv4 is unavailable.
                        addProperty("strategy", "prefer_ipv4")
                    })
                })
            })

            add("route", JsonObject().apply {
                add("rules", JsonArray().apply {
                    // Domain sniffing is still mandatory when smart domain routing is on.
                    // With smart routing off, fast mode can skip this per-flow inspection.
                    if (!fastForwarding || smartRouting) {
                        add(JsonObject().apply { addProperty("action", "sniff") })
                    }
                    if (enableDnsRules) {
                        add(JsonObject().apply {
                            addProperty("protocol", "dns")
                            addProperty("action", "hijack-dns")
                        })
                    }

                    if (smartRouting) {
                        // Root stop/start does not replace Android's active network. An app
                        // may keep an IP learned while capture was off. Recover only reviewed
                        // X hosts with a recognizable protocol, before terminal package rules.
                        addDestinationRecoveryRules(this, selectedNode, proxy, routingPolicy)
                        // Package identity is available in System / Root. HEV still
                        // evaluates the shared domain policy when the owner is unavailable.
                        routingPolicy.proxyPackageGroups.forEach { packages ->
                            add(JsonObject().apply {
                                addProperty("type", "logical")
                                addProperty("mode", "and")
                                add("rules", JsonArray().apply {
                                    add(JsonObject().apply {
                                        add("package_name", JsonArray().apply { packages.forEach(::add) })
                                    })
                                    add(JsonObject().apply {
                                        addProperty("ip_is_private", true)
                                        addProperty("invert", true)
                                    })
                                })
                                addProperty("action", "route")
                                addProperty("outbound", TAG_PROXY)
                            })
                        }
                        addDomainRoutingRules(this, selectedNode, proxy, routingPolicy)

                        // Minimal observed-IP exceptions must never override known
                        // international services or app-identity guards above.
                        if (routingPolicy.directIpExceptions.isNotEmpty()) {
                            add(JsonObject().apply {
                                add("ip_cidr", JsonArray().apply {
                                    routingPolicy.directIpExceptions.forEach(::add)
                                })
                                addProperty("outbound", TAG_DIRECT)
                            })
                        }

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
                            // This evaluates actual destination IPs. The pinned core does not
                            // auto-resolve domain-form HEV requests for IP rules. Deliberately
                            // avoid a global resolve action: its failure aborts otherwise viable
                            // remote-DNS proxy connections, and adds a serial DNS round trip.
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
            .filterNot { it == SELF_PACKAGE }
            .distinct()
            .sorted()
            .toList()

        when (mode) {
            PerAppPolicyResolver.MODE_ALL -> Unit
            PerAppPolicyResolver.MODE_ALLOW_LIST -> {
                require(selected.isNotEmpty()) { "仅选中代理模式至少需要选择 1 个应用" }
                // Real-device verified: include mode must keep RRBOX itself in the VPN
                // UID set; RRBOX outbound sockets are then released with protect(fd).
                val allowed = (selected + SELF_PACKAGE).distinct().sorted()
                tun.add("include_package", JsonArray().apply { allowed.forEach(::add) })
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

    private fun addDomainRoutingRules(
        rules: JsonArray, selectedNode: ProxyNode, proxy: JsonObject, routingPolicy: RoutingPolicySnapshot
    ) {
        var directStarted = false
        routingPolicy.domainRules.forEach { policy ->
            if (!directStarted && policy.destination == DomesticRoutingPolicy.Destination.DIRECT) {
                // Preserve the existing priority of every explicit proxy domain policy.
                // Recovery must precede the ordinary DIRECT rule that would consume it.
                addWeChatIpv6RecoveryRules(rules, selectedNode, proxy, routingPolicy)
                directStarted = true
            }
            rules.add(domainCondition(policy).apply {
                addProperty("outbound", when (policy.destination) {
                    DomesticRoutingPolicy.Destination.DIRECT -> TAG_DIRECT
                    DomesticRoutingPolicy.Destination.PROXY -> TAG_PROXY
                })
            })
        }
    }

    private fun addDestinationRecoveryRules(
        rules: JsonArray, selectedNode: ProxyNode, proxy: JsonObject, routingPolicy: RoutingPolicySnapshot
    ) {
        // Imported native JSON can carry a different effective server from its display
        // model. Exclude both so recovery never rewrites either bootstrap identity.
        val hosts = XDestinationRecoveryPolicy.hosts(routingPolicy, selectedNode.server, primitiveString(proxy.get("server")))
        if (hosts.isEmpty()) return
        for (host in hosts) {
            rules.add(destinationRecoveryCondition(listOf(host)).apply {
                addProperty("action", "route-options")
                // The replacement is exactly the matched host, never a suffix or a shared
                // CDN name. Core route-options preserves the port and UDP reply identity.
                addProperty("override_address", host)
            })
        }
        rules.add(destinationRecoveryCondition(hosts).apply {
            // resolve alone leaves an IP destination unchanged. Only after the override
            // can trusted DNS replace it. Resolve these hosts for IP-only/packetaddr
            // outbounds too; all other HEV domains retain their remote-DNS behavior.
            addProperty("action", "resolve")
            addProperty("server", DNS_REMOTE)
            addProperty("strategy", "prefer_ipv4")
        })
        // Keep the existing terminal package/domain rules and their priority unchanged.
        // A second override at the terminal rule would erase the resolved addresses.
    }

    private fun addWeChatIpv6RecoveryRules(
        rules: JsonArray, selectedNode: ProxyNode, proxy: JsonObject, routingPolicy: RoutingPolicySnapshot
    ) {
        for (host in WeChatIpv6RecoveryPolicy.hosts(
            routingPolicy, selectedNode.server, primitiveString(proxy.get("server"))
        )) {
            rules.add(JsonObject().apply {
                addProperty("type", "logical")
                addProperty("mode", "and")
                add("rules", JsonArray().apply {
                    add(JsonObject().apply { add("domain", JsonArray().apply { add(host) }) })
                    add(JsonObject().apply { addProperty("ip_version", 6) })
                    add(JsonObject().apply { addProperty("ip_is_private", true); addProperty("invert", true) })
                    add(JsonObject().apply {
                        add("ip_cidr", JsonArray().apply {
                            WeChatIpv6RecoveryPolicy.excludedDestinationCidrs.forEach(::add)
                        })
                        addProperty("invert", true)
                    })
                    // Observed failures are TCP. Keep all UDP, including QUIC and calls,
                    // on its original route; a DNS reverse-map alone cannot identify P2P.
                    add(JsonObject().apply { addProperty("network", "tcp") })
                    add(JsonObject().apply {
                        addProperty("type", "logical")
                        addProperty("mode", "or")
                        add("rules", JsonArray().apply {
                            add(JsonObject().apply {
                                add("protocol", JsonArray().apply { add("http"); add("tls") })
                            })
                            // WeChat's short/long transport need not be HTTP or TLS. Only
                            // its verified owner may use an exact domain recovered by DNS
                            // reverse mapping for opaque TCP.
                            add(JsonObject().apply {
                                add("package_name", JsonArray().apply {
                                    add(WeChatIpv6RecoveryPolicy.PACKAGE_NAME)
                                })
                            })
                        })
                    })
                })
                // One terminal action keeps package precedence and preserves the original
                // TCP port. The direct dialer resolves the replacement using
                // dns-direct and provides IPv4/IPv6 fallback; no unrelated IP is hard-mapped.
                addProperty("action", "route")
                addProperty("outbound", TAG_DIRECT)
                addProperty("override_address", host)
            })
        }
    }

    private fun destinationRecoveryCondition(hosts: List<String>) = JsonObject().apply {
        addProperty("type", "logical")
        addProperty("mode", "and")
        add("rules", JsonArray().apply {
            add(JsonObject().apply { add("domain", JsonArray().apply { hosts.forEach(::add) }) })
            add(JsonObject().apply {
                add("protocol", JsonArray().apply { add("http"); add("tls"); add("quic") })
            })
            add(JsonObject().apply {
                add("network", JsonArray().apply { add("tcp"); add("udp") })
            })
            add(JsonObject().apply { addProperty("ip_is_private", true); addProperty("invert", true) })
            add(JsonObject().apply {
                add("ip_cidr", JsonArray().apply { XDestinationRecoveryPolicy.excludedDestinationCidrs.forEach(::add) })
                addProperty("invert", true)
            })
        })
    }

    private fun domainCondition(policy: DomesticRoutingPolicy.DomainRule): JsonObject =
        JsonObject().apply {
            if (policy.domains.isNotEmpty()) {
                add("domain", JsonArray().apply { policy.domains.forEach(::add) })
            }
            if (policy.suffixes.isNotEmpty()) {
                add("domain_suffix", JsonArray().apply { policy.suffixes.forEach(::add) })
            }
        }

    private fun buildDnsConfig(
        selectedNode: ProxyNode,
        smartRouting: Boolean,
        ruleSets: ChinaRuleSetManager.Paths?,
        routingPolicy: RoutingPolicySnapshot
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
                routingPolicy.domainRules.forEach { policy ->
                    add(domainCondition(policy).apply {
                        addProperty("action", "route")
                        addProperty("server", when (policy.destination) {
                            DomesticRoutingPolicy.Destination.DIRECT -> DNS_DIRECT
                            DomesticRoutingPolicy.Destination.PROXY -> DNS_REMOTE
                        })
                    })
                }
                if (ruleSets != null) {
                    add(JsonObject().apply {
                        add("rule_set", JsonArray().apply { add(RULE_GEOSITE_CN) })
                        addProperty("action", "route")
                        addProperty("server", DNS_DIRECT)
                    })
                }
            }
        })
        addProperty("final", DNS_REMOTE)
        addProperty("strategy", "prefer_ipv4")
        // Recover domains for System TUN flows that cannot be sniffed. This is best-effort:
        // application-owned DoH/HTTPDNS and shared-IP ambiguity still need IP/default rules.
        // sing-box 1.14 isolates DNS caches by transport already; independent_cache is deprecated.
        addProperty("reverse_mapping", smartRouting)
    }

    /**
     * Native sing-box JSON is protocol-complete, so preserve it after sanitizing
     * version-specific fields. Share-link nodes that can be represented directly
     * keep raw JSON as well; older basic links still use fallback builders.
     */
    private fun buildSelectedOutbound(node: ProxyNode): JsonObject? {
        if (node.rawJson.isNotBlank()) {
            val expectedType = when (node.type) {
                ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS -> "vless"
                ProtocolType.VMESS_WS_ARGO, ProtocolType.VMESS_TLS -> "vmess"
                ProtocolType.HYSTERIA1 -> "hysteria"
                ProtocolType.HYSTERIA2 -> "hysteria2"
                ProtocolType.TUIC_V5 -> "tuic"
                ProtocolType.ANYTLS -> "anytls"
                ProtocolType.NAIVE_H2, ProtocolType.NAIVE_H3 -> "naive"
                ProtocolType.TROJAN -> "trojan"
                ProtocolType.SHADOWSOCKS -> "shadowsocks"
                ProtocolType.SOCKS -> "socks"
                ProtocolType.HTTP -> "http"
                ProtocolType.SSH -> "ssh"
                ProtocolType.WIREGUARD -> "wireguard"
                ProtocolType.SHADOWTLS -> "shadowtls"
                ProtocolType.SNELL -> "snell"
                ProtocolType.TOR -> "tor"
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
            ProtocolType.HYSTERIA1,
            ProtocolType.ANYTLS,
            ProtocolType.NAIVE_H2,
            ProtocolType.NAIVE_H3,
            ProtocolType.SOCKS,
            ProtocolType.HTTP,
            ProtocolType.SSH,
            ProtocolType.WIREGUARD,
            ProtocolType.SHADOWTLS,
            ProtocolType.SNELL,
            ProtocolType.TOR,
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
                    addProperty("insecure", node.allowInsecure)
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
                addProperty("insecure", node.allowInsecure)
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
                addProperty("insecure", node.allowInsecure)
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
                    addProperty("insecure", node.allowInsecure)
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
                addProperty("insecure", node.allowInsecure)
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

    /** Only true control/system outbounds are rejected. Valid proxy outbounds such
     * as HTTP/SOCKS/SSH/WireGuard are allowed when they come from native raw JSON. */
    private val INTERNAL_OUTBOUND_TYPES = setOf("direct", "block", "dns", "selector", "urltest")
}

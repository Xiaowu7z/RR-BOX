package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode

object ConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private const val TAG_PROXY = "proxy"
    private const val TAG_DIRECT = "direct"
    private const val TAG_BLOCK = "block"
    private const val TAG_DNS_OUT = "dns-out"
    private val RESERVED_TAGS = setOf(TAG_PROXY, TAG_DIRECT, TAG_BLOCK, TAG_DNS_OUT)

    /**
     * 生成 sing-box 配置。
     * 不支持/缺失关键字段的节点会被安全跳过（不作为出站生成），
     * 仅当选中节点本身无法生成时抛出异常，由 UI 层给出提示。
     */
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

        // 2. DNS: 代理服务器域名走直连解析防死锁
        root.add("dns", JsonObject().apply {
            val servers = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "udp")
                    addProperty("tag", "dns-direct")
                    addProperty("server", "223.5.5.5")
                    addProperty("detour", TAG_DIRECT)
                })
                add(JsonObject().apply {
                    addProperty("type", "udp")
                    addProperty("tag", "dns-remote")
                    addProperty("server", "1.1.1.1")
                    addProperty("detour", TAG_PROXY)
                })
            }
            add("servers", servers)

            val rules = JsonArray().apply {
                // 节点服务器域名直连解析
                val domains = JsonArray().apply {
                    allNodes.forEach { if (it.server.isNotEmpty()) add(it.server) }
                }
                add(JsonObject().apply {
                    add("domain", domains)
                    addProperty("server", "dns-direct")
                })
                // 国内域名直连解析（用已知域名列表，不用 rule_set）
                if (smartRouting) {
                    add(JsonObject().apply {
                        val cnDomains = JsonArray().apply {
                            add("cn")
                            add("baidu.com")
                            add("qq.com")
                            add("taobao.com")
                            add("tmall.com")
                            add("jd.com")
                            add("alipay.com")
                            add("aliyun.com")
                            add("tencent.com")
                            add("weixin.com")
                            add("wechat.com")
                            add("bilibili.com")
                            add("163.com")
                            add("126.com")
                            add("sina.com")
                            add("sohu.com")
                            add("ifeng.com")
                            add("zhihu.com")
                        }
                        add("domain", cnDomains)
                        addProperty("server", "dns-direct")
                    })
                }
            }
            add("rules", rules)
            addProperty("final", "dns-remote")
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

        // 4. Outbounds: 选中节点作为默认代理，其余节点供分流选择
        val usedTags = mutableSetOf<String>()

        // 先构建选中节点（tag 固定为 proxy）
        val selectedOutbound = buildOutboundJson(selectedNode)
            ?: throw IllegalArgumentException(
                "选中节点「${selectedNode.tag}」缺少可用的协议配置，无法连接。请换一个节点。"
            )
        selectedOutbound.addProperty("tag", TAG_PROXY)
        usedTags.add(TAG_PROXY)

        root.add("outbounds", JsonArray().apply {
            add(selectedOutbound)
            add(JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", TAG_DIRECT)
            })
            add(JsonObject().apply {
                addProperty("type", "block")
                addProperty("tag", TAG_BLOCK)
            })
            add(JsonObject().apply {
                addProperty("type", "dns")
                addProperty("tag", TAG_DNS_OUT)
            })

            allNodes.forEach { node ->
                if (node.id == selectedNode.id) return@forEach
                val built = buildOutboundJson(node) ?: return@forEach
                // 节点 tag 可能重复（不同订阅同名）或撞保留字，做唯一化
                var tag = node.tag.ifBlank { "Node-${node.server}" }
                if (tag in usedTags || tag in RESERVED_TAGS) {
                    var suffix = 2
                    while ("${tag}_$suffix" in usedTags) suffix++
                    tag = "${tag}_$suffix"
                }
                built.addProperty("tag", tag)
                usedTags.add(tag)
                add(built)
            }
        })

        // 5. Route
        root.add("route", JsonObject().apply {
            val rules = JsonArray().apply {
                // DNS Hijacking
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("outbound", TAG_DNS_OUT)
                })

                // 服务器域名/IP 直连防环路
                add(JsonObject().apply {
                    val serverList = JsonArray().apply {
                        allNodes.forEach { if (it.server.isNotEmpty()) add(it.server) }
                    }
                    add("domain", serverList)
                    addProperty("outbound", TAG_DIRECT)
                })

                // 分应用路由
                appRoutes.forEach { appRoute ->
                    when (appRoute.routeMode) {
                        "DIRECT", "BYPASS" -> {
                            add(JsonObject().apply {
                                val pkg = JsonArray().apply { add(appRoute.packageName) }
                                add("package_name", pkg)
                                addProperty("outbound", TAG_DIRECT)
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

                // 私有 IP 与局域网直连
                add(JsonObject().apply {
                    val ip = JsonArray().apply {
                        add("10.0.0.0/8")
                        add("172.16.0.0/12")
                        add("192.168.0.0/16")
                        add("127.0.0.0/8")
                    }
                    add("ip_cidr", ip)
                    addProperty("outbound", TAG_DIRECT)
                })

                if (smartRouting) {
                    add(JsonObject().apply {
                        // 用私有 + CGNAT IP 段粗略代表国内流量兜底直连
                        // 精确分流由应用层（package_name）按需处理
                        val cnIps = JsonArray().apply {
                            // 国内主流云厂商常用 IP 段示例
                            add("36.0.0.0/12")     // 部分电信
                            add("39.0.0.0/8")      // 移动
                            add("42.0.0.0/8")      // 部分国内
                            add("43.0.0.0/8")
                            add("47.74.0.0/16")    // 阿里云
                            add("47.75.0.0/16")
                            add("47.76.0.0/16")
                            add("59.108.0.0/16")   // 联通
                            add("101.6.0.0/16")    // 教育网
                            add("103.0.0.0/8")
                            add("106.11.0.0/16")   // 阿里云
                            add("110.242.0.0/16")  // 百度云
                            add("111.0.0.0/10")
                            add("112.0.0.0/10")
                            add("114.114.114.0/24")
                            add("115.0.0.0/8")
                            add("116.0.0.0/8")
                            add("117.0.0.0/8")
                            add("118.0.0.0/8")
                            add("119.0.0.0/8")
                            add("120.0.0.0/8")
                            add("121.0.0.0/8")
                            add("122.0.0.0/8")
                            add("123.0.0.0/8")
                            add("124.0.0.0/8")
                            add("125.0.0.0/8")
                            add("139.155.0.0/16")  // 腾讯云
                            add("140.143.0.0/16")
                            add("150.109.0.0/16")  // 腾讯云
                            add("180.76.76.0/24")  // 百度
                            add("202.0.0.0/8")
                            add("203.0.0.0/8")
                            add("211.0.0.0/8")
                            add("218.0.0.0/8")
                            add("219.0.0.0/8")
                            add("220.0.0.0/8")
                            add("221.0.0.0/8")
                            add("222.0.0.0/8")
                            add("223.0.0.0/8")
                        }
                        add("ip_cidr", cnIps)
                        addProperty("outbound", TAG_DIRECT)
                    })
                }

                // 兜底走代理
                add(JsonObject().apply {
                    addProperty("outbound", TAG_PROXY)
                })
            }
            add("rules", rules)
            addProperty("auto_detect_interface", true)
        })

        return gson.toJson(root)
    }

    /** 返回 null 表示该节点无法生成可用出站（自动跳过，不影响其他节点） */
    private fun buildOutboundJson(node: ProxyNode): JsonObject? {
        // RRVPS sing-box JSON 订阅原文优先透传
        if (node.rawJson.isNotBlank()) {
            try {
                val parsed = JsonParser.parseString(node.rawJson).asJsonObject
                if (parsed.get("type")?.asString.isNullOrBlank()) return null
                parsed.remove("tag")
                return parsed
            } catch (e: Exception) {
                // fallthrough，尝试手写
            }
        }

        val obj = JsonObject()
        obj.addProperty("tag", node.tag)

        return try {
            when (node.type) {
                ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS -> buildVless(obj, node)
                ProtocolType.HYSTERIA2 -> buildHysteria2(obj, node)
                ProtocolType.TUIC_V5 -> buildTuic(obj, node)
                ProtocolType.VMESS_TLS, ProtocolType.VMESS_WS_ARGO -> buildVmess(obj, node)
                ProtocolType.TROJAN -> buildTrojan(obj, node)
                ProtocolType.SHADOWSOCKS -> buildShadowsocks(obj, node)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildVless(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "vless")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("uuid", node.uuidOrPassword)
        if (node.flow.isNotEmpty()) obj.addProperty("flow", node.flow)
        addTransport(obj, node)
        if (node.tlsEnabled) {
            val tls = JsonObject().apply { addProperty("enabled", true) }
            if (node.sni.isNotEmpty()) tls.addProperty("server_name", node.sni)
            if (node.type == ProtocolType.VLESS_REALITY) {
                if (node.realityPublicKey.isBlank()) return null // reality 缺公钥不可用
                tls.add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
                tls.add("reality", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("public_key", node.realityPublicKey)
                    addProperty("short_id", node.realityShortId.ifBlank { "" })
                })
            } else {
                // 普通 TLS（可选 utls 指纹，缺省即标准指纹）
                tls.add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
            }
            obj.add("tls", tls)
        }
        return obj
    }

    private fun buildHysteria2(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "hysteria2")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("password", node.uuidOrPassword)
        if (node.hoppingPorts.isNotEmpty()) {
            obj.addProperty("ports", node.hoppingPorts)
        }
        if (node.obfs.isNotEmpty()) {
            val obfs = JsonObject().apply {
                addProperty("type", node.obfs)
                if (node.obfsPassword.isNotEmpty()) addProperty("password", node.obfsPassword)
            }
            obj.add("obfs", obfs)
        }
        obj.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
            // hy2 惯例多数自签证书，insecure 兜底，避免握手失败
            addProperty("insecure", true)
        })
        return obj
    }

    private fun buildTuic(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "tuic")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("uuid", node.uuidOrPassword)
        if (node.extraPassword.isNotEmpty()) obj.addProperty("password", node.extraPassword)
        if (node.alpn.isNotEmpty()) {
            val alpn = JsonArray()
            node.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alpn.add(it) }
            if (alpn.size() > 0) obj.add("alpn", alpn)
        }
        obj.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
            addProperty("insecure", true)
        })
        return obj
    }

    private fun buildVmess(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "vmess")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("uuid", node.uuidOrPassword)
        addTransport(obj, node)
        if (node.tlsEnabled) {
            obj.add("tls", JsonObject().apply {
                addProperty("enabled", true)
                if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
                add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", "chrome")
                })
            })
        }
        return obj
    }

    private fun buildTrojan(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "trojan")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("password", node.uuidOrPassword)
        addTransport(obj, node)
        obj.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (node.sni.isNotEmpty()) addProperty("server_name", node.sni)
            if (node.alpn.isNotEmpty()) {
                val alpn = JsonArray()
                node.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alpn.add(it) }
                if (alpn.size() > 0) add("alpn", alpn)
            }
        })
        return obj
    }

    private fun buildShadowsocks(obj: JsonObject, node: ProxyNode): JsonObject? {
        if (node.uuidOrPassword.isBlank() || node.ssMethod.isBlank() || node.server.isBlank()) return null
        obj.addProperty("type", "shadowsocks")
        obj.addProperty("server", node.server)
        obj.addProperty("server_port", node.serverPort)
        obj.addProperty("method", node.ssMethod)
        obj.addProperty("password", node.uuidOrPassword)
        return obj
    }

    /** ws/grpc/tcp 传输层（vmess/trojan/vless 共用） */
    private fun addTransport(obj: JsonObject, node: ProxyNode) {
        when (node.network.lowercase()) {
            "ws" -> {
                val transport = JsonObject().apply {
                    addProperty("type", "ws")
                    if (node.path.isNotEmpty()) addProperty("path", node.path)
                    if (node.host.isNotEmpty()) {
                        add("headers", JsonObject().apply { addProperty("Host", node.host) })
                    }
                }
                obj.add("transport", transport)
            }
            "grpc" -> {
                val transport = JsonObject().apply {
                    addProperty("type", "grpc")
                    if (node.path.isNotEmpty()) addProperty("service_name", node.path)
                }
                obj.add("transport", transport)
            }
            else -> {
                // tcp: 无 transport 块
            }
        }
    }
}

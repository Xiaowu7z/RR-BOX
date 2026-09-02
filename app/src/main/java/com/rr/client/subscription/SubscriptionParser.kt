package com.rr.client.subscription

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.model.SubscriptionUserInfo

object SubscriptionParser {

    fun parseUserInfoHeader(headerValue: String?): SubscriptionUserInfo {
        if (headerValue.isNullOrBlank()) return SubscriptionUserInfo()
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        headerValue.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                val key = kv[0].trim()
                val value = kv[1].trim().toLongOrNull() ?: 0L
                when (key.lowercase()) {
                    "upload" -> upload = value
                    "download" -> download = value
                    "total" -> total = value
                    "expire" -> expire = value
                }
            }
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

    /**
     * 解析订阅内容（支持 sing-box JSON / base64 URI / 纯文本 URI 三种格式）。
     * profileId/profileName 用于多订阅聚合：节点 id 带订阅前缀，避免跨组冲突。
     */
    fun parseContent(
        rawContent: String,
        profileId: String,
        profileName: String
    ): List<ProxyNode> {
        val trimmed = rawContent.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseSingBoxJson(trimmed, profileId, profileName)
        }

        val decoded = robustBase64Decode(trimmed) ?: trimmed
        val nodes = mutableListOf<ProxyNode>()
        var index = 0
        decoded.lineSequence().forEach { line ->
            val node = parseUri(line.trim(), profileId, profileName, index)
            if (node != null) {
                nodes.add(node)
                index++
            }
        }
        return nodes
    }

    private fun robustBase64Decode(input: String): String? {
        val candidates = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP or Base64.URL_SAFE,
            Base64.DEFAULT or Base64.URL_SAFE
        )
        for (flags in candidates) {
            try {
                val bytes = Base64.decode(input.trim(), flags)
                val text = String(bytes, Charsets.UTF_8)
                if (text.isNotBlank() && text.contains("\n")) return text
            } catch (_: Exception) {
            }
        }
        // 单行（可能无换行）也尝试按 base64 解码后按行解析
        try {
            val bytes = Base64.decode(input.trim(), Base64.DEFAULT)
            val text = String(bytes, Charsets.UTF_8)
            if (text.isNotBlank() && (text.contains("://") || text.startsWith("vmess://"))) return text
        } catch (_: Exception) {
        }
        return null
    }

    // ---------- sing-box JSON 订阅 ----------

    private fun parseSingBoxJson(jsonStr: String, profileId: String, profileName: String): List<ProxyNode> {
        val list = mutableListOf<ProxyNode>()
        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            if (root.has("outbounds")) {
                val outbounds = root.getAsJsonArray("outbounds")
                outbounds.forEachIndexed { index, el ->
                    val obj = el.asJsonObject
                    val type = obj.get("type")?.asString?.orEmpty() ?: ""
                    val tag = obj.get("tag")?.asString?.orEmpty() ?: "Node-$index"
                    val server = obj.get("server")?.asString?.orEmpty() ?: ""

                    val isTrafficFakeTag = tag.contains("已用") || tag.contains("剩余") || tag.contains("到期") || tag.contains("流量")
                    val isInternalType = type in INTERNAL_OUTBOUND_TYPES
                    if (server.isNotEmpty() && !isTrafficFakeTag && !isInternalType) {
                        val protocol = when (type) {
                            "vless" -> {
                                val realityEnabled = runCatching {
                                    obj.getAsJsonObject("tls").getAsJsonObject("reality").get("enabled").asBoolean
                                }.getOrDefault(false)
                                if (realityEnabled) ProtocolType.VLESS_REALITY else ProtocolType.VLESS_TLS
                            }
                            "hysteria2", "hy2" -> ProtocolType.HYSTERIA2
                            "tuic" -> ProtocolType.TUIC_V5
                            "vmess" -> {
                                val network = runCatching { obj.getAsJsonObject("transport").get("type").asString }.getOrDefault("")
                                if (network == "ws" || network == "grpc") ProtocolType.VMESS_WS_ARGO else ProtocolType.VMESS_TLS
                            }
                            "trojan" -> ProtocolType.TROJAN
                            "shadowsocks", "ss" -> ProtocolType.SHADOWSOCKS
                            "anytls" -> ProtocolType.ANYTLS
                            "naive" -> ProtocolType.NAIVE_H2
                            else -> ProtocolType.CUSTOM
                        }

                        val transport = runCatching { obj.getAsJsonObject("transport") }.getOrNull()
                        val tls = runCatching { obj.getAsJsonObject("tls") }.getOrNull()
                        val reality = runCatching { tls?.getAsJsonObject("reality") }.getOrNull()
                        val headers = runCatching { transport?.getAsJsonObject("headers") }.getOrNull()
                        val alpn = runCatching {
                            tls?.getAsJsonArray("alpn")?.joinToString(",") { it.asString }
                        }.getOrDefault("")

                        list.add(
                            ProxyNode(
                                id = nodeId(profileId, server, portOf(obj), index),
                                tag = tag,
                                type = protocol,
                                server = server,
                                serverPort = portOf(obj),
                                uuidOrPassword = obj.get("uuid")?.asString?.orEmpty()
                                    ?: obj.get("password")?.asString?.orEmpty() ?: "",
                                flow = obj.get("flow")?.asString?.orEmpty() ?: "",
                                realityPublicKey = reality?.get("public_key")?.asString?.orEmpty() ?: "",
                                realityShortId = reality?.get("short_id")?.asString?.orEmpty() ?: "",
                                sni = tls?.get("server_name")?.asString?.orEmpty() ?: "",
                                network = transport?.get("type")?.asString?.orEmpty() ?: "tcp",
                                path = transport?.get("path")?.asString?.orEmpty() ?: "",
                                host = headers?.get("Host")?.asString?.orEmpty() ?: "",
                                alpn = alpn,
                                ssMethod = obj.get("method")?.asString?.orEmpty() ?: "",
                                obfs = obj.get("obfs")?.asString?.orEmpty() ?: "",
                                obfsPassword = obj.get("obfs-password")?.asString ?: obj.get("obfs_password")?.asString?.orEmpty() ?: "",
                                profileId = profileId,
                                profileName = profileName,
                                tlsEnabled = tls?.get("enabled")?.asBoolean ?: (tls != null),
                                extraPassword = if (type == "tuic") obj.get("password")?.asString?.orEmpty() ?: "" else "",
                                rawJson = obj.toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // 单个字段解析异常不阻断整批
        }
        return list
    }

    private fun portOf(obj: com.google.gson.JsonObject): Int =
        obj.get("server_port")?.asInt ?: obj.get("serverPort")?.asInt ?: 443

    // ---------- URI 订阅（base64 解码后逐行） ----------

    private fun parseUri(line: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        if (line.isBlank() || !line.contains("://")) return null
        val scheme = line.substringBefore("://").lowercase()
        return try {
            when (scheme) {
                "vless" -> parseVlessUri(Uri.parse(line), profileId, profileName, index)
                "hy2", "hysteria2" -> parseHy2Uri(Uri.parse(line), profileId, profileName, index)
                "tuic" -> parseTuicUri(Uri.parse(line), profileId, profileName, index)
                "trojan" -> parseTrojanUri(Uri.parse(line), profileId, profileName, index)
                "ss" -> parseSsUri(line, profileId, profileName, index)
                "vmess" -> parseVmessUri(line, profileId, profileName, index)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVlessUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val tag = Uri.decode(uri.fragment ?: "VLESS-$host")
        val security = uri.getQueryParameter("security") ?: "reality"
        val network = uri.getQueryParameter("type")?.lowercase() ?: "tcp"
        val isReality = security == "reality" || !uri.getQueryParameter("pbk").isNullOrEmpty()

        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = if (isReality) ProtocolType.VLESS_REALITY else ProtocolType.VLESS_TLS,
            server = host,
            serverPort = port,
            uuidOrPassword = uri.userInfo ?: "",
            flow = uri.getQueryParameter("flow") ?: "",
            realityPublicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: "",
            realityShortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: "",
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("serverName") ?: host,
            network = network,
            path = uri.getQueryParameter("path") ?: "",
            host = uri.getQueryParameter("host") ?: "",
            tlsEnabled = isReality || security == "tls",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseHy2Uri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val tag = Uri.decode(uri.fragment ?: "HY2-$host")
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = ProtocolType.HYSTERIA2,
            server = host,
            serverPort = port,
            uuidOrPassword = uri.userInfo ?: "",
            sni = uri.getQueryParameter("sni") ?: host,
            obfs = uri.getQueryParameter("obfs") ?: "",
            obfsPassword = uri.getQueryParameter("obfs-password")
                ?: uri.getQueryParameter("obfsPassword") ?: "",
            hoppingPorts = uri.getQueryParameter("ports") ?: "",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseTuicUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val tag = Uri.decode(uri.fragment ?: "TUIC-$host")
        val userInfo = uri.userInfo ?: ""
        val uuid = userInfo.substringBefore(":")
        val password = userInfo.substringAfter(":", "")

        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = ProtocolType.TUIC_V5,
            server = host,
            serverPort = port,
            uuidOrPassword = uuid,
            extraPassword = password,
            sni = uri.getQueryParameter("sni") ?: host,
            alpn = uri.getQueryParameter("alpn") ?: "",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseTrojanUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val tag = Uri.decode(uri.fragment ?: "Trojan-$host")
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = ProtocolType.TROJAN,
            server = host,
            serverPort = port,
            uuidOrPassword = uri.userInfo ?: "",
            sni = uri.getQueryParameter("sni") ?: host,
            network = uri.getQueryParameter("type")?.lowercase() ?: "tcp",
            path = uri.getQueryParameter("path") ?: "",
            host = uri.getQueryParameter("host") ?: "",
            alpn = uri.getQueryParameter("alpn") ?: "",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseSsUri(line: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        var body = line.removePrefix("ss://").substringBefore("#")
        val tag = Uri.decode(line.substringAfter("#", "").ifEmpty { "SS-${index + 1}" })

        var method = ""
        var password = ""
        var host = ""
        var port = 443

        if (body.contains("@")) {
            // SIP002: userinfo@host:port
            var userInfo = body.substringBefore("@")
            val hostPort = body.substringAfter("@").substringBefore("/")
            // userinfo 可能是 base64(method:password)
            val decodedUser = decodeSip002UserInfo(userInfo)
            method = decodedUser.first
            password = decodedUser.second
            host = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
        } else {
            // 旧格式: base64(method:password@host:port)
            val decoded = base64DecodeLenient(body) ?: return null
            val atSplit = decoded.split("@")
            if (atSplit.size < 2) return null
            val cred = atSplit[0]
            val hostPort = atSplit[1]
            method = cred.substringBefore(":")
            password = cred.substringAfter(":", "")
            host = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
        }
        if (method.isEmpty() || host.isEmpty()) return null

        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = ProtocolType.SHADOWSOCKS,
            server = host,
            serverPort = port,
            ssMethod = method,
            uuidOrPassword = password,
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun decodeSip002UserInfo(userInfo: String): Pair<String, String> {
        if (userInfo.contains(":")) {
            val m = userInfo.substringBefore(":")
            val p = userInfo.substringAfter(":", "")
            if (m.isNotEmpty()) return m to p
        }
        val decoded = base64DecodeLenient(userInfo) ?: return "" to ""
        return decoded.substringBefore(":") to decoded.substringAfter(":", "")
    }

    private fun parseVmessUri(line: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        val b64 = line.removePrefix("vmess://").substringBefore("#").substringBefore("/")
        val decoded = base64DecodeLenient(b64) ?: return null
        val obj = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull() ?: return null
        val host = obj.get("add")?.asString ?: return null
        val port = obj.get("port")?.asInt ?: 443
        val tag = Uri.decode(obj.get("ps")?.asString?.orEmpty() ?: obj.get("name")?.asString?.orEmpty() ?: "VMess-$host")
        val network = obj.get("net")?.asString?.orEmpty()?.lowercase() ?: "tcp"
        val isWss = network == "ws" || network == "grpc"

        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = tag,
            type = if (isWss) ProtocolType.VMESS_WS_ARGO else ProtocolType.VMESS_TLS,
            server = host,
            serverPort = port,
            uuidOrPassword = obj.get("id")?.asString?.orEmpty() ?: "",
            sni = obj.get("sni")?.asString ?: obj.get("host")?.asString?.orEmpty() ?: "",
            network = network,
            path = obj.get("path")?.asString?.orEmpty() ?: "",
            host = obj.get("host")?.asString?.orEmpty() ?: "",
            tlsEnabled = obj.get("tls")?.asString == "tls",
            profileId = profileId,
            profileName = profileName
        )
    }

    // ---------- 工具 ----------

    private fun nodeId(profileId: String, host: String, port: Int, index: Int): String {
        val safeHost = host.replace(":", "_")
        return "$profileId-${safeHost}_$port-$index"
    }

    private fun base64DecodeLenient(input: String): String? {
        val normalized = input.trim().replace('_', '/').replace('-', '+')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                String(Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private val INTERNAL_OUTBOUND_TYPES = setOf(
        "direct", "block", "dns", "selector", "urltest", "http", "socks", "wireguard", "ssh"
    )
}

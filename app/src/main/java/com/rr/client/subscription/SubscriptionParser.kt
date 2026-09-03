package com.rr.client.subscription

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
            val pieces = part.trim().split("=", limit = 2)
            if (pieces.size != 2) return@forEach
            val value = pieces[1].trim().toLongOrNull() ?: 0L
            when (pieces[0].trim().lowercase()) {
                "upload" -> upload = value
                "download" -> download = value
                "total" -> total = value
                "expire" -> expire = value
            }
        }
        return SubscriptionUserInfo(upload, download, total, expire)
    }

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
        decoded.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val node = parseUri(line, profileId, profileName, nodes.size)
            if (node != null) nodes += node
        }
        return nodes
    }

    private fun robustBase64Decode(input: String): String? {
        val compact = input.filterNot(Char::isWhitespace)
        val candidates = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP or Base64.URL_SAFE,
            Base64.DEFAULT or Base64.URL_SAFE
        )
        for (flags in candidates) {
            val text = runCatching {
                String(Base64.decode(compact, flags), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (text.isNotBlank() && (text.contains("://") || text.contains('\n'))) {
                return text
            }
        }
        return null
    }

    private fun parseSingBoxJson(
        json: String,
        profileId: String,
        profileName: String
    ): List<ProxyNode> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return emptyList()
        val outbounds = root.get("outbounds")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: return emptyList()

        return buildList {
            outbounds.forEachIndexed { index, element ->
                val node = runCatching {
                    parseSingBoxOutbound(
                        element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@runCatching null,
                        profileId,
                        profileName,
                        index
                    )
                }.getOrNull()
                if (node != null) add(node)
            }
        }
    }

    private fun parseSingBoxOutbound(
        obj: JsonObject,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val type = objString(obj, "type").lowercase()
        if (type.isBlank() || type in INTERNAL_OUTBOUND_TYPES) return null

        val tag = objString(obj, "tag").ifBlank { "Node-$index" }
        if (tag.contains("已用") || tag.contains("剩余") || tag.contains("到期") || tag.contains("流量")) {
            return null
        }

        val server = objString(obj, "server")
        if (server.isBlank()) return null

        val tls = objObject(obj, "tls")
        val transport = objObject(obj, "transport")
        val reality = tls?.let { objObject(it, "reality") }
        val headers = transport?.let { objObject(it, "headers") }
        val obfsObject = objObject(obj, "obfs")

        val protocol = when (type) {
            "vless" -> if (objBoolean(reality, "enabled")) {
                ProtocolType.VLESS_REALITY
            } else {
                ProtocolType.VLESS_TLS
            }

            "hysteria2", "hy2" -> ProtocolType.HYSTERIA2
            "tuic" -> ProtocolType.TUIC_V5
            "vmess" -> when (objString(transport, "type").lowercase()) {
                "ws", "grpc" -> ProtocolType.VMESS_WS_ARGO
                else -> ProtocolType.VMESS_TLS
            }

            "trojan" -> ProtocolType.TROJAN
            "shadowsocks", "ss" -> ProtocolType.SHADOWSOCKS
            "anytls" -> ProtocolType.ANYTLS
            "naive" -> ProtocolType.NAIVE_H2
            else -> ProtocolType.CUSTOM
        }

        val password = objString(obj, "password")
        val uuid = objString(obj, "uuid")
        val alpn = readStringList(tls?.get("alpn") ?: obj.get("alpn")).joinToString(",")
        val hoppingPorts = readStringList(
            obj.get("server_ports") ?: obj.get("ports") ?: obj.get("mport")
        ).joinToString(",")

        return ProxyNode(
            id = nodeId(profileId, server, portOf(obj), index),
            tag = tag,
            type = protocol,
            server = server,
            serverPort = portOf(obj),
            uuidOrPassword = uuid.ifBlank { password },
            flow = objString(obj, "flow"),
            realityPublicKey = objString(reality, "public_key"),
            realityShortId = objString(reality, "short_id"),
            sni = objString(tls, "server_name").ifBlank { objString(obj, "sni") },
            network = objString(transport, "type").ifBlank { "tcp" },
            path = objString(transport, "path").ifBlank { objString(transport, "service_name") },
            host = objString(headers, "Host").ifBlank { objString(headers, "host") },
            alpn = alpn,
            tlsEnabled = tls != null && (!tls.has("enabled") || objBoolean(tls, "enabled")),
            ssMethod = objString(obj, "method"),
            obfs = objString(obfsObject, "type").ifBlank { objString(obj, "obfs") },
            obfsPassword = objString(obfsObject, "password")
                .ifBlank { objString(obj, "obfs-password") }
                .ifBlank { objString(obj, "obfs_password") },
            hoppingPorts = hoppingPorts,
            extraPassword = if (type == "tuic") password else "",
            profileId = profileId,
            profileName = profileName,
            rawJson = obj.toString()
        )
    }

    private fun parseUri(
        line: String,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        if (line.isBlank() || !line.contains("://")) return null
        val scheme = line.substringBefore("://").lowercase()
        return runCatching {
            when (scheme) {
                "vless" -> parseVlessUri(Uri.parse(line), profileId, profileName, index)
                "hy2", "hysteria2" -> parseHy2Uri(Uri.parse(line), profileId, profileName, index)
                "tuic" -> parseTuicUri(Uri.parse(line), profileId, profileName, index)
                "trojan" -> parseTrojanUri(Uri.parse(line), profileId, profileName, index)
                "ss" -> parseSsUri(line, profileId, profileName, index)
                "vmess" -> parseVmessUri(line, profileId, profileName, index)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVlessUri(
        uri: Uri,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val security = uri.getQueryParameter("security").orEmpty().ifBlank { "reality" }
        val publicKey = uri.getQueryParameter("pbk")
            ?: uri.getQueryParameter("publicKey")
            ?: ""
        val isReality = security == "reality" || publicKey.isNotBlank()
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = decodedFragment(uri, "VLESS-$host"),
            type = if (isReality) ProtocolType.VLESS_REALITY else ProtocolType.VLESS_TLS,
            server = host,
            serverPort = port,
            uuidOrPassword = Uri.decode(uri.userInfo.orEmpty()),
            flow = uri.getQueryParameter("flow").orEmpty(),
            realityPublicKey = publicKey,
            realityShortId = uri.getQueryParameter("sid")
                ?: uri.getQueryParameter("shortId")
                ?: "",
            sni = uri.getQueryParameter("sni")
                ?: uri.getQueryParameter("serverName")
                ?: host,
            network = uri.getQueryParameter("type").orEmpty().ifBlank { "tcp" }.lowercase(),
            path = uri.getQueryParameter("path").orEmpty(),
            host = uri.getQueryParameter("host").orEmpty(),
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            tlsEnabled = isReality || security == "tls",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseHy2Uri(
        uri: Uri,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val hopping = uri.getQueryParameter("mport")
            ?: uri.getQueryParameter("ports")
            ?: uri.getQueryParameter("server_ports")
            ?: ""
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = decodedFragment(uri, "HY2-$host"),
            type = ProtocolType.HYSTERIA2,
            server = host,
            serverPort = port,
            uuidOrPassword = Uri.decode(uri.userInfo.orEmpty()),
            sni = uri.getQueryParameter("sni") ?: host,
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            obfs = uri.getQueryParameter("obfs").orEmpty(),
            obfsPassword = uri.getQueryParameter("obfs-password")
                ?: uri.getQueryParameter("obfsPassword")
                ?: "",
            hoppingPorts = hopping,
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseTuicUri(
        uri: Uri,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val userInfo = Uri.decode(uri.userInfo.orEmpty())
        val separator = userInfo.indexOf(':')
        val uuid = if (separator > 0) userInfo.substring(0, separator) else userInfo
        val password = if (separator > 0) userInfo.substring(separator + 1) else ""
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = decodedFragment(uri, "TUIC-$host"),
            type = ProtocolType.TUIC_V5,
            server = host,
            serverPort = port,
            uuidOrPassword = uuid,
            extraPassword = password,
            sni = uri.getQueryParameter("sni") ?: host,
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseTrojanUri(
        uri: Uri,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = decodedFragment(uri, "Trojan-$host"),
            type = ProtocolType.TROJAN,
            server = host,
            serverPort = port,
            uuidOrPassword = Uri.decode(uri.userInfo.orEmpty()),
            sni = uri.getQueryParameter("sni") ?: host,
            network = uri.getQueryParameter("type").orEmpty().ifBlank { "tcp" }.lowercase(),
            path = uri.getQueryParameter("path").orEmpty(),
            host = uri.getQueryParameter("host").orEmpty(),
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseSsUri(
        line: String,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val body = line.removePrefix("ss://").substringBefore("#")
        val tag = Uri.decode(line.substringAfter("#", "").ifBlank { "SS-${index + 1}" })
        val method: String
        val password: String
        val host: String
        val port: Int

        if (body.contains("@")) {
            val decodedUser = decodeSip002UserInfo(body.substringBefore("@"))
            val hostPort = body.substringAfter("@").substringBefore("/")
            method = decodedUser.first
            password = decodedUser.second
            host = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":", "443").toIntOrNull() ?: 443
        } else {
            val decoded = base64DecodeLenient(body) ?: return null
            val userAndHost = decoded.split("@", limit = 2)
            if (userAndHost.size != 2) return null
            method = userAndHost[0].substringBefore(":")
            password = userAndHost[0].substringAfter(":", "")
            host = userAndHost[1].substringBefore(":")
            port = userAndHost[1].substringAfter(":", "443").toIntOrNull() ?: 443
        }

        if (method.isBlank() || host.isBlank()) return null
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
        if (userInfo.contains(':')) {
            val method = userInfo.substringBefore(":")
            if (method.isNotBlank()) return method to userInfo.substringAfter(":", "")
        }
        val decoded = base64DecodeLenient(userInfo) ?: return "" to ""
        return decoded.substringBefore(":") to decoded.substringAfter(":", "")
    }

    private fun parseVmessUri(
        line: String,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val encoded = line.removePrefix("vmess://").substringBefore("#").substringBefore("/")
        val decoded = base64DecodeLenient(encoded) ?: return null
        val obj = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull()
            ?: return null
        val host = objString(obj, "add")
        if (host.isBlank()) return null
        val port = objInt(obj, "port", 443)
        val network = objString(obj, "net").ifBlank { "tcp" }.lowercase()
        val tag = objString(obj, "ps").ifBlank { objString(obj, "name") }.ifBlank { "VMess-$host" }
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = Uri.decode(tag),
            type = if (network == "ws" || network == "grpc") {
                ProtocolType.VMESS_WS_ARGO
            } else {
                ProtocolType.VMESS_TLS
            },
            server = host,
            serverPort = port,
            uuidOrPassword = objString(obj, "id"),
            sni = objString(obj, "sni").ifBlank { objString(obj, "host") },
            network = network,
            path = objString(obj, "path"),
            host = objString(obj, "host"),
            alpn = objString(obj, "alpn"),
            tlsEnabled = objString(obj, "tls") == "tls",
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun decodedFragment(uri: Uri, fallback: String): String =
        Uri.decode(uri.fragment ?: fallback)

    private fun nodeId(profileId: String, host: String, port: Int, index: Int): String =
        "$profileId-${host.replace(':', '_')}_$port-$index"

    private fun base64DecodeLenient(input: String): String? {
        val normalized = input.trim().replace('_', '/').replace('-', '+')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.recoverCatching {
            String(Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun objString(obj: JsonObject?, key: String): String {
        val value = obj?.get(key) ?: return ""
        return if (value.isJsonPrimitive) runCatching { value.asString }.getOrDefault("") else ""
    }

    private fun objInt(obj: JsonObject?, key: String, fallback: Int = 0): Int {
        val value = obj?.get(key) ?: return fallback
        return if (value.isJsonPrimitive) runCatching { value.asInt }.getOrDefault(fallback) else fallback
    }

    private fun objBoolean(obj: JsonObject?, key: String): Boolean {
        val value = obj?.get(key) ?: return false
        return if (value.isJsonPrimitive) runCatching { value.asBoolean }.getOrDefault(false) else false
    }

    private fun objObject(obj: JsonObject?, key: String): JsonObject? =
        obj?.get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun readStringList(element: JsonElement?): List<String> = when {
        element == null || element.isJsonNull -> emptyList()
        element.isJsonArray -> element.asJsonArray.mapNotNull { item ->
            item.takeIf(JsonElement::isJsonPrimitive)?.let { runCatching { it.asString }.getOrNull() }
        }
        element.isJsonPrimitive -> runCatching { element.asString }.getOrDefault("")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        else -> emptyList()
    }

    private fun portOf(obj: JsonObject): Int {
        val direct = objInt(obj, "server_port")
            .takeIf { it > 0 }
            ?: objInt(obj, "serverPort").takeIf { it > 0 }
        if (direct != null) return direct

        val firstRange = readStringList(obj.get("server_ports")).firstOrNull().orEmpty()
        return firstRange.substringBefore(':').substringBefore('-').toIntOrNull() ?: 443
    }

    private val INTERNAL_OUTBOUND_TYPES = setOf(
        "direct",
        "block",
        "dns",
        "selector",
        "urltest",
        "http",
        "socks",
        "wireguard",
        "ssh"
    )
}

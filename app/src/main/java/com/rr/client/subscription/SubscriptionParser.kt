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

/**
 * Node-only subscription/import parser.
 *
 * Priority:
 *  1. Native sing-box JSON (full config, one outbound, or outbound array)
 *  2. Clash/Mihomo YAML `proxies:` converted to native sing-box outbounds
 *  3. Base64 or plain share-link lists
 *
 * Every imported node keeps a native raw outbound whenever possible so new
 * protocol fields survive until libbox performs the authoritative checkConfig.
 */
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
        val trimmed = rawContent.trim().removePrefix("\uFEFF")
        if (trimmed.isBlank()) return emptyList()

        parseJsonContent(trimmed, profileId, profileName)?.takeIf { it.isNotEmpty() }?.let { return it }
        parseClashContent(trimmed, profileId, profileName)?.takeIf { it.isNotEmpty() }?.let { return it }

        val decoded = robustBase64Decode(trimmed)
        if (!decoded.isNullOrBlank() && decoded != trimmed) {
            parseJsonContent(decoded, profileId, profileName)?.takeIf { it.isNotEmpty() }?.let { return it }
            parseClashContent(decoded, profileId, profileName)?.takeIf { it.isNotEmpty() }?.let { return it }
            return parseShareLines(decoded, profileId, profileName)
        }

        return parseShareLines(trimmed, profileId, profileName)
    }

    private fun parseJsonContent(
        text: String,
        profileId: String,
        profileName: String
    ): List<ProxyNode>? {
        val element = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return null
        val outboundElements: List<JsonObject> = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            element.isJsonObject -> {
                val root = element.asJsonObject
                when {
                    root.get("outbounds")?.isJsonArray == true -> root.getAsJsonArray("outbounds")
                        .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                    root.has("type") -> listOf(root)
                    else -> return emptyList()
                }
            }
            else -> return emptyList()
        }

        return buildList {
            outboundElements.forEachIndexed { index, obj ->
                runCatching { parseSingBoxOutbound(obj, profileId, profileName, index) }
                    .getOrNull()?.let(::add)
            }
        }
    }

    private fun parseClashContent(
        text: String,
        profileId: String,
        profileName: String
    ): List<ProxyNode>? {
        if (!text.contains(Regex("(?m)^\\s*proxies\\s*:"))) return null
        val converted = ClashSubscriptionConverter.convert(text) ?: return emptyList()
        return parseJsonContent(converted, profileId, profileName)
    }

    private fun parseShareLines(
        content: String,
        profileId: String,
        profileName: String
    ): List<ProxyNode> = buildList {
        content.lineSequence()
            .flatMap { line -> line.split(Regex("\\s+(?=[A-Za-z][A-Za-z0-9+.-]*://)"), limit = 0).asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line ->
                parseUri(line, profileId, profileName, size)?.let(::add)
            }
    }

    private fun robustBase64Decode(input: String): String? {
        val compact = input.filterNot(Char::isWhitespace)
        if (compact.length < 8) return null
        val candidates = listOf(
            Base64.DEFAULT,
            Base64.NO_WRAP or Base64.URL_SAFE,
            Base64.DEFAULT or Base64.URL_SAFE
        )
        for (flags in candidates) {
            val text = runCatching { String(Base64.decode(compact, flags), Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.isNotBlank() && (text.contains("://") || text.contains('\n') || text.trimStart().startsWith("{"))) {
                return text
            }
        }
        return null
    }

    private fun parseSingBoxOutbound(
        obj: JsonObject,
        profileId: String,
        profileName: String,
        index: Int
    ): ProxyNode? {
        val type = objString(obj, "type").lowercase()
        if (type.isBlank() || type in INTERNAL_OUTBOUND_TYPES) return null

        val tag = objString(obj, "tag").ifBlank { "${type.uppercase()}-${index + 1}" }
        if (tag.contains("已用") || tag.contains("剩余") || tag.contains("到期") || tag.contains("流量")) return null

        val server = objString(obj, "server").ifBlank { firstPeerServer(obj) }
        if (server.isBlank()) return null
        val port = portOf(obj)
        if (port !in 1..65535) return null

        val tls = objObject(obj, "tls")
        val transport = objObject(obj, "transport")
        val reality = tls?.let { objObject(it, "reality") }
        val headers = transport?.let { objObject(it, "headers") }
        val obfsObject = objObject(obj, "obfs")

        val protocol = when (type) {
            "vless" -> if (objBoolean(reality, "enabled")) ProtocolType.VLESS_REALITY else ProtocolType.VLESS_TLS
            "hysteria" -> ProtocolType.HYSTERIA1
            "hysteria2", "hy2" -> ProtocolType.HYSTERIA2
            "tuic" -> ProtocolType.TUIC_V5
            "vmess" -> when (objString(transport, "type").lowercase()) {
                "ws", "grpc" -> ProtocolType.VMESS_WS_ARGO
                else -> ProtocolType.VMESS_TLS
            }
            "trojan" -> ProtocolType.TROJAN
            "shadowsocks", "ss" -> ProtocolType.SHADOWSOCKS
            "socks" -> ProtocolType.SOCKS
            "http" -> ProtocolType.HTTP
            "ssh" -> ProtocolType.SSH
            "wireguard" -> ProtocolType.WIREGUARD
            "shadowtls" -> ProtocolType.SHADOWTLS
            "snell" -> ProtocolType.SNELL
            "tor" -> ProtocolType.TOR
            "anytls" -> ProtocolType.ANYTLS
            "naive" -> if (objBoolean(obj, "quic")) ProtocolType.NAIVE_H3 else ProtocolType.NAIVE_H2
            else -> ProtocolType.CUSTOM
        }

        val password = objString(obj, "password")
            .ifBlank { objString(obj, "auth_str") }
            .ifBlank { objString(obj, "private_key") }
        val uuid = objString(obj, "uuid")
        val alpn = readStringList(tls?.get("alpn") ?: obj.get("alpn")).joinToString(",")
        val hoppingPorts = readStringList(obj.get("server_ports") ?: obj.get("ports") ?: obj.get("mport")).joinToString(",")

        return ProxyNode(
            id = nodeId(profileId, server, port, index),
            tag = tag,
            type = protocol,
            server = server,
            serverPort = port,
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
                "hysteria" -> parseHysteria1Uri(Uri.parse(line), profileId, profileName, index)
                "tuic" -> parseTuicUri(Uri.parse(line), profileId, profileName, index)
                "trojan" -> parseTrojanUri(Uri.parse(line), profileId, profileName, index)
                "ss" -> parseSsUri(line, profileId, profileName, index)
                "vmess" -> parseVmessUri(line, profileId, profileName, index)
                "anytls" -> parseAnyTlsUri(Uri.parse(line), profileId, profileName, index)
                "socks", "socks5", "socks4", "socks4a" -> parseSocksUri(Uri.parse(line), scheme, profileId, profileName, index)
                "http", "https" -> parseHttpProxyUri(Uri.parse(line), scheme == "https", profileId, profileName, index)
                "ssh" -> parseSshUri(Uri.parse(line), profileId, profileName, index)
                "naive+https", "naive+quic" -> parseNaiveUri(Uri.parse(line), scheme == "naive+quic", profileId, profileName, index)
                "shadowtls" -> parseShadowTlsUri(Uri.parse(line), profileId, profileName, index)
                "snell" -> parseSnellUri(Uri.parse(line), profileId, profileName, index)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVlessUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val security = uri.getQueryParameter("security").orEmpty().ifBlank { "reality" }
        val publicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: ""
        val isReality = security.equals("reality", true) || publicKey.isNotBlank()
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = decodedFragment(uri, "VLESS-$host"),
            type = if (isReality) ProtocolType.VLESS_REALITY else ProtocolType.VLESS_TLS,
            server = host,
            serverPort = port,
            uuidOrPassword = Uri.decode(uri.userInfo.orEmpty()),
            flow = uri.getQueryParameter("flow").orEmpty(),
            realityPublicKey = publicKey,
            realityShortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: "",
            sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("serverName") ?: host,
            network = uri.getQueryParameter("type").orEmpty().ifBlank { "tcp" }.lowercase(),
            path = uri.getQueryParameter("path").orEmpty(),
            host = uri.getQueryParameter("host").orEmpty(),
            alpn = uri.getQueryParameter("alpn").orEmpty(),
            tlsEnabled = isReality || security.equals("tls", true),
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseHy2Uri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val hopping = uri.getQueryParameter("mport") ?: uri.getQueryParameter("ports")
            ?: uri.getQueryParameter("server_ports") ?: ""
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
            obfsPassword = uri.getQueryParameter("obfs-password") ?: uri.getQueryParameter("obfsPassword") ?: "",
            hoppingPorts = hopping,
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun parseHysteria1Uri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val auth = Uri.decode(uri.userInfo.orEmpty()).ifBlank {
            uri.getQueryParameter("auth") ?: uri.getQueryParameter("auth_str") ?: ""
        }
        val raw = JsonObject().apply {
            addProperty("type", "hysteria")
            addProperty("tag", decodedFragment(uri, "HY1-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            if (auth.isNotBlank()) addProperty("auth_str", auth)
            uri.getQueryParameter("obfs")?.takeIf(String::isNotBlank)?.let { addProperty("obfs", it) }
            uri.getQueryParameter("upmbps")?.toIntOrNull()?.takeIf { it > 0 }?.let { addProperty("up_mbps", it) }
            uri.getQueryParameter("downmbps")?.toIntOrNull()?.takeIf { it > 0 }?.let { addProperty("down_mbps", it) }
            addTls(this, uri, host)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseTuicUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
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

    private fun parseTrojanUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
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

    private fun parseAnyTlsUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val raw = JsonObject().apply {
            addProperty("type", "anytls")
            addProperty("tag", decodedFragment(uri, "AnyTLS-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("password", Uri.decode(uri.userInfo.orEmpty()))
            addTls(this, uri, host)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseSocksUri(uri: Uri, scheme: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 1080
        val (user, password) = splitUserInfo(uri)
        val raw = JsonObject().apply {
            addProperty("type", "socks")
            addProperty("tag", decodedFragment(uri, "SOCKS-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("version", when (scheme) { "socks4" -> "4"; "socks4a" -> "4a"; else -> "5" })
            if (user.isNotBlank()) addProperty("username", user)
            if (password.isNotBlank()) addProperty("password", password)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseHttpProxyUri(uri: Uri, tls: Boolean, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: if (tls) 443 else 80
        val (user, password) = splitUserInfo(uri)
        val raw = JsonObject().apply {
            addProperty("type", "http")
            addProperty("tag", decodedFragment(uri, "${if (tls) "HTTPS" else "HTTP"}-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            if (user.isNotBlank()) addProperty("username", user)
            if (password.isNotBlank()) addProperty("password", password)
            if (tls) addTls(this, uri, host)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseSshUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 22
        val (user, password) = splitUserInfo(uri)
        if (user.isBlank()) return null
        val raw = JsonObject().apply {
            addProperty("type", "ssh")
            addProperty("tag", decodedFragment(uri, "SSH-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("user", user)
            if (password.isNotBlank()) addProperty("password", password)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseNaiveUri(uri: Uri, quic: Boolean, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val (user, password) = splitUserInfo(uri)
        val raw = JsonObject().apply {
            addProperty("type", "naive")
            addProperty("tag", decodedFragment(uri, "Naive-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            if (user.isNotBlank()) addProperty("username", user)
            if (password.isNotBlank()) addProperty("password", password)
            if (quic) addProperty("quic", true)
            addTls(this, uri, host)
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseShadowTlsUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val raw = JsonObject().apply {
            addProperty("type", "shadowtls")
            addProperty("tag", decodedFragment(uri, "ShadowTLS-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("version", uri.getQueryParameter("version")?.toIntOrNull() ?: 3)
            addProperty("password", Uri.decode(uri.userInfo.orEmpty()))
            uri.getQueryParameter("sni")?.takeIf(String::isNotBlank)?.let { addProperty("server_name", it) }
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseSnellUri(uri: Uri, profileId: String, profileName: String, index: Int): ProxyNode? {
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val raw = JsonObject().apply {
            addProperty("type", "snell")
            addProperty("tag", decodedFragment(uri, "Snell-$host"))
            addProperty("server", host)
            addProperty("server_port", port)
            addProperty("version", uri.getQueryParameter("version")?.toIntOrNull() ?: 4)
            addProperty("psk", Uri.decode(uri.userInfo.orEmpty()))
        }
        return parseSingBoxOutbound(raw, profileId, profileName, index)
    }

    private fun parseSsUri(line: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        val body = line.removePrefix("ss://").substringBefore("#")
        val tag = Uri.decode(line.substringAfter("#", "").ifBlank { "SS-${index + 1}" })
        val method: String
        val password: String
        val host: String
        val port: Int

        if (body.contains("@")) {
            val decodedUser = decodeSip002UserInfo(body.substringBefore("@"))
            val hostPort = body.substringAfter("@").substringBefore("/").substringBefore("?")
            val parsed = parseHostPort(hostPort, 443) ?: return null
            method = decodedUser.first
            password = decodedUser.second
            host = parsed.first
            port = parsed.second
        } else {
            val decoded = base64DecodeLenient(body) ?: return null
            val split = decoded.lastIndexOf('@')
            if (split <= 0) return null
            val user = decoded.substring(0, split)
            val parsed = parseHostPort(decoded.substring(split + 1), 443) ?: return null
            method = user.substringBefore(":")
            password = user.substringAfter(":", "")
            host = parsed.first
            port = parsed.second
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
        val decodedDirect = Uri.decode(userInfo)
        if (decodedDirect.contains(':')) {
            val method = decodedDirect.substringBefore(":")
            if (method.isNotBlank()) return method to decodedDirect.substringAfter(":", "")
        }
        val decoded = base64DecodeLenient(userInfo) ?: return "" to ""
        return decoded.substringBefore(":") to decoded.substringAfter(":", "")
    }

    private fun parseVmessUri(line: String, profileId: String, profileName: String, index: Int): ProxyNode? {
        // Do not substringBefore("/"): standard Base64 payloads may legitimately contain '/'.
        val encoded = line.removePrefix("vmess://").substringBefore("#").trim()
        val decoded = base64DecodeLenient(encoded) ?: return null
        val obj = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull() ?: return null
        val host = objString(obj, "add")
        if (host.isBlank()) return null
        val port = objInt(obj, "port", 443)
        val network = objString(obj, "net").ifBlank { "tcp" }.lowercase()
        val tag = objString(obj, "ps").ifBlank { objString(obj, "name") }.ifBlank { "VMess-$host" }
        return ProxyNode(
            id = nodeId(profileId, host, port, index),
            tag = Uri.decode(tag),
            type = if (network == "ws" || network == "grpc") ProtocolType.VMESS_WS_ARGO else ProtocolType.VMESS_TLS,
            server = host,
            serverPort = port,
            uuidOrPassword = objString(obj, "id"),
            sni = objString(obj, "sni").ifBlank { objString(obj, "host") },
            network = network,
            path = objString(obj, "path"),
            host = objString(obj, "host"),
            alpn = objString(obj, "alpn"),
            tlsEnabled = objString(obj, "tls").equals("tls", true),
            profileId = profileId,
            profileName = profileName
        )
    }

    private fun addTls(outbound: JsonObject, uri: Uri, fallbackSni: String) {
        outbound.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            addProperty("server_name", uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: fallbackSni)
            if (queryBoolean(uri, "insecure") || queryBoolean(uri, "allowInsecure") || queryBoolean(uri, "skip-cert-verify")) {
                addProperty("insecure", true)
            }
            uri.getQueryParameter("alpn")?.takeIf(String::isNotBlank)?.let { value ->
                add("alpn", JsonArray().apply { value.split(',').map(String::trim).filter(String::isNotBlank).forEach(::add) })
            }
        })
    }

    private fun splitUserInfo(uri: Uri): Pair<String, String> {
        val decoded = Uri.decode(uri.userInfo.orEmpty())
        val separator = decoded.indexOf(':')
        return if (separator < 0) decoded to "" else decoded.substring(0, separator) to decoded.substring(separator + 1)
    }

    private fun parseHostPort(raw: String, defaultPort: Int): Pair<String, Int>? {
        val text = raw.trim()
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end <= 1) return null
            val host = text.substring(1, end)
            val port = text.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val split = text.lastIndexOf(':')
        if (split > 0 && text.indexOf(':') == split) {
            return text.substring(0, split) to (text.substring(split + 1).toIntOrNull() ?: defaultPort)
        }
        return text to defaultPort
    }

    private fun decodedFragment(uri: Uri, fallback: String): String = Uri.decode(uri.fragment ?: fallback)

    private fun queryBoolean(uri: Uri, key: String): Boolean {
        val value = uri.getQueryParameter(key)?.lowercase() ?: return false
        return value == "1" || value == "true" || value == "yes"
    }

    private fun nodeId(profileId: String, host: String, port: Int, index: Int): String =
        "$profileId-${host.replace(':', '_')}_$port-$index"

    private fun base64DecodeLenient(input: String): String? {
        val normalized = input.trim().replace('_', '/').replace('-', '+')
        val padded = normalized.padEnd((normalized.length + 3) / 4 * 4, '=')
        return runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }
            .recoverCatching { String(Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }
            .getOrNull()
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
            .split(',').map(String::trim).filter(String::isNotEmpty)
        else -> emptyList()
    }

    private fun firstPeerServer(obj: JsonObject): String {
        val peers = obj.get("peers")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return ""
        val first = peers.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return ""
        return objString(first, "server")
    }

    private fun portOf(obj: JsonObject): Int {
        val direct = objInt(obj, "server_port").takeIf { it > 0 }
            ?: objInt(obj, "serverPort").takeIf { it > 0 }
        if (direct != null) return direct

        val peer = obj.get("peers")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val peerPort = objInt(peer, "server_port").takeIf { it > 0 }
        if (peerPort != null) return peerPort

        val firstRange = readStringList(obj.get("server_ports")).firstOrNull().orEmpty()
        return firstRange.substringBefore(':').substringBefore('-').toIntOrNull() ?: 443
    }

    private val INTERNAL_OUTBOUND_TYPES = setOf("direct", "block", "dns", "selector", "urltest")
}

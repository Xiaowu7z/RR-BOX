package com.rr.client.sharing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.TrafficInfoNode
import com.rr.client.subscription.SubscriptionUrlNormalizer
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

enum class SharePayloadKind { LINK, JSON }

data class SharePayload(
    val title: String,
    val text: String,
    val fileName: String,
    val mimeType: String,
    val kind: SharePayloadKind,
    val notice: String? = null
)

sealed interface SharePayloadResult {
    data class Success(val payload: SharePayload) : SharePayloadResult
    data class Failure(val reason: String) : SharePayloadResult
}

/** Pure serialization. Payloads contain credentials: callers must never send them to logs. */
object SharePayloadBuilder {
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun subscription(name: String, originalUrl: String): SharePayloadResult {
        if (originalUrl.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return tooLarge()
        if (originalUrl.isBlank() || originalUrl.any { it.isWhitespace() || it.isISOControl() } ||
            runCatching { SubscriptionUrlNormalizer.candidates(originalUrl) }.isFailure
        ) return SharePayloadResult.Failure("订阅地址无效，请检查原始 HTTP/HTTPS 地址")
        // Preserve signed queries, escaping, order, casing, fragments and explicit ports exactly.
        val notice = if (originalUrl.startsWith("http://", true) || originalUrl.startsWith("https://", true)) null
            else "已保留原订阅地址，接收客户端需要支持省略协议头的地址。"
        return success(name.ifBlank { "订阅" }, originalUrl, SharePayloadKind.LINK, "subscription.txt", notice)
    }

    fun node(node: ProxyNode): SharePayloadResult {
        if (TrafficInfoNode.isInfoNode(node)) return SharePayloadResult.Failure("流量信息条目不是可连接节点")
        if (node.rawJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return tooLarge()
        val raw = if (node.rawJson.isNotBlank()) runCatching {
            JsonParser.parseString(node.rawJson).asJsonObject
        }.getOrNull() ?: return SharePayloadResult.Failure("节点原始配置无效，无法完整分享") else null
        val outbound = raw ?: basicOutbound(node)
            ?: return SharePayloadResult.Failure("该节点缺少可完整分享的原始配置")
        val renameRaw = raw != null && node.nameOverrideOnly &&
            raw.get("tag")?.takeIf { it.isJsonPrimitive }?.asString != node.tag
        if (renameRaw) outbound.addProperty("tag", node.tag)
        val link = runCatching { standardLink(outbound, node.tag) }.getOrNull()
        return if (link != null) success(node.tag, link, SharePayloadKind.LINK, "node.txt")
        else success(node.tag, if (raw != null && !renameRaw) node.rawJson else outbound.toString(),
            SharePayloadKind.JSON, "node.json", "已保留节点原始配置；引用的其他出站、外部证书和文件需在接收端配置。")
    }

    private fun success(title: String, text: String, kind: SharePayloadKind, file: String, notice: String? = null): SharePayloadResult =
        if (text.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) tooLarge()
        else SharePayloadResult.Success(SharePayload(title, text, file,
            if (kind == SharePayloadKind.JSON) "application/json" else "text/plain", kind, notice))

    private fun tooLarge() = SharePayloadResult.Failure("分享内容过大，最多允许 8 MiB")

    /** Whitelists are intentionally narrow: any extra field, including nested fields, keeps JSON. */
    private fun standardLink(outbound: JsonObject, name: String): String? {
        // Some existing Android URI import paths decode userinfo/fragment twice.
        // Keep literal percent sequences in JSON until those importers are migrated.
        if ('%' in name || listOf("uuid", "password", "username").any { key ->
                outbound.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.contains('%') == true
            }) return null
        val r = Fields(outbound)
        val type = r.string("type")
        r.string("tag")
        val server = r.string("server")
        val port = r.int("server_port")
        if (server.isBlank() || port !in 1..65535) return null
        val host = server.removePrefix("[").removeSuffix("]")
        val authority = "${if (':' in host) "[$host]" else host}:$port"
        if (runCatching { URI("share://$authority").host }.getOrNull().isNullOrBlank()) return null
        val query = linkedMapOf<String, String>()
        val tls = r.obj("tls")?.let { tlsQuery(it, query) } ?: false
        val transport = r.obj("transport")?.let { transportQuery(it, query) } ?: "tcp"
        val scheme: String
        val auth: String
        when (type) {
            "vless" -> {
                // The existing importer rebuilds Reality with Chrome uTLS and ordinary TLS
                // without uTLS. A different fingerprint must remain native JSON.
                require(if (query.containsKey("pbk")) query["fp"] == "chrome" else query["fp"] == null)
                scheme = "vless"
                auth = encode(r.string("uuid").also { require(it.isNotBlank()) })
                query["encryption"] = "none"
                query["security"] = if (query.containsKey("pbk")) "reality" else if (tls) "tls" else "none"
                query["type"] = transport
                r.string("flow").takeIf { it.isNotEmpty() }?.let { query["flow"] = it }
            }
            "vmess" -> {
                // These are the fields understood by RRBOX's VMess importer and common v2 links.
                require(query["pbk"] == null && query["fp"] == if (tls) "chrome" else null)
                val security = r.string("security", "auto")
                require(security == "auto" && r.int("alter_id", 0) == 0)
                val uuid = r.string("uuid").also { require(it.isNotBlank()) }
                r.finish()
                val json = JsonObject().apply {
                    addProperty("v", "2"); addProperty("ps", name)
                    addProperty("add", server); addProperty("port", port); addProperty("id", uuid)
                    addProperty("aid", 0); addProperty("scy", security)
                    addProperty("net", transport); addProperty("type", "none")
                    addProperty("host", query["host"].orEmpty())
                    addProperty("path", query["path"] ?: query["serviceName"].orEmpty())
                    addProperty("tls", if (tls) "tls" else "")
                    addProperty("sni", query["sni"].orEmpty()); addProperty("alpn", query["alpn"].orEmpty())
                    addProperty("allowInsecure", query["allowInsecure"] == "1")
                    query["fp"]?.let { addProperty("fp", it) }
                }
                return "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
            }
            "trojan", "anytls", "hysteria2" -> {
                require(tls && query["pbk"] == null && query["fp"] == null)
                scheme = type
                auth = encode(r.string("password").also { require(it.isNotBlank()) })
                if (type == "trojan") query["type"] = transport else require(transport == "tcp")
                if (type == "hysteria2") {
                    query.remove("allowInsecure")?.let { query["insecure"] = it }
                    r.obj("obfs")?.let { obfs ->
                        val fields = Fields(obfs)
                        query["obfs"] = fields.string("type")
                        query["obfs-password"] = fields.string("password")
                        fields.finish()
                    }
                    // Port hopping has competing URI syntaxes. Keep native server_ports in JSON.
                }
            }
            "tuic" -> {
                require(tls && transport == "tcp" && query["pbk"] == null && query["fp"] == null)
                // The basic importer fixes these three settings; alternatives must remain raw.
                require(r.string("congestion_control") == "bbr")
                require(r.bool("zero_rtt_handshake"))
                require(r.string("udp_relay_mode") == "native")
                query["congestion_control"] = "bbr"; query["udp_relay_mode"] = "native"
                query["zero_rtt_handshake"] = "1"
                scheme = "tuic"
                auth = encode(r.string("uuid").also { require(it.isNotBlank()) }) + ":" + encode(r.string("password"))
            }
            "shadowsocks" -> {
                require(!tls && transport == "tcp" && query.isEmpty())
                scheme = "ss"
                val method = r.string("method").also { require(it.isNotBlank()) }
                val password = r.string("password").also { require(it.isNotBlank()) }
                auth = if (method.startsWith("2022-")) encode(method) + ":" + encode(password)
                    else Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$password".toByteArray(Charsets.UTF_8))
            }
            "socks", "http" -> {
                require(transport == "tcp" && query["pbk"] == null && query["fp"] == null)
                if (type == "socks") require(!tls && query.isEmpty())
                scheme = if (type == "http") { if (tls) "https" else "http" } else when (r.string("version", "5")) {
                    "4" -> "socks4"; "4a" -> "socks4a"; "5" -> "socks5"; else -> return null
                }
                val user = r.string("username")
                // A colon in username cannot round trip through the current URI importer.
                require(':' !in user)
                val password = r.string("password")
                auth = if (user.isEmpty() && password.isEmpty()) "" else encode(user) + ":" + encode(password)
            }
            else -> return null
        }
        r.finish()
        val params = query.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$scheme://${if (auth.isEmpty()) "" else "$auth@"}$authority" +
            (if (params.isEmpty()) "" else "?$params") + "#${encode(name)}"
    }

    private fun tlsQuery(obj: JsonObject, query: MutableMap<String, String>): Boolean {
        // Native and URI-import defaults differ when enabled is absent; do not guess.
        require(obj.has("enabled"))
        val r = Fields(obj)
        val enabled = r.bool("enabled", true)
        if (!enabled) { r.finish(); return false }
        r.string("server_name").takeIf { it.isNotEmpty() }?.let { query["sni"] = it }
        if (r.bool("insecure")) query["allowInsecure"] = "1"
        r.array("alpn")?.let { array ->
            val values = array.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { s -> require(s.isNotEmpty() && ',' !in s && s == s.trim()) }
            }
            if (values.isNotEmpty()) query["alpn"] = values.joinToString(",")
        }
        r.obj("utls")?.let { value ->
            val u = Fields(value)
            require(u.bool("enabled")); query["fp"] = u.string("fingerprint"); u.finish()
        }
        r.obj("reality")?.let { value ->
            val reality = Fields(value)
            require(reality.bool("enabled"))
            query["pbk"] = reality.string("public_key").also { require(it.isNotEmpty()) }
            reality.string("short_id").takeIf { it.isNotEmpty() }?.let { query["sid"] = it }
            reality.finish()
        }
        r.finish()
        return true
    }

    private fun transportQuery(obj: JsonObject, query: MutableMap<String, String>): String {
        val r = Fields(obj)
        val type = r.string("type")
        when (type) {
            "ws" -> {
                r.string("path").takeIf { it.isNotEmpty() }?.let { query["path"] = it }
                r.obj("headers")?.let { headers ->
                    // Only the exact Host key is representable; additional headers require JSON.
                    val h = Fields(headers)
                    h.string("Host").takeIf { it.isNotEmpty() }?.let { query["host"] = it }
                    h.finish()
                }
            }
            "grpc" -> r.string("service_name").takeIf { it.isNotEmpty() }?.let {
                query["serviceName"] = it
                query["path"] = it // RRBOX's existing importer reads path for gRPC.
            }
            else -> error("Unrepresentable transport")
        }
        r.finish()
        return type
    }

    /** Mirrors ConfigBuilder's supported legacy model defaults; unknown protocols require raw. */
    private fun basicOutbound(node: ProxyNode): JsonObject? {
        if (node.server.isBlank() || node.serverPort !in 1..65535 || node.uuidOrPassword.isBlank()) return null
        val type = when (node.type) {
            ProtocolType.VLESS_REALITY, ProtocolType.VLESS_TLS -> "vless"
            ProtocolType.VMESS_TLS, ProtocolType.VMESS_WS_ARGO -> "vmess"
            ProtocolType.HYSTERIA2 -> "hysteria2"
            ProtocolType.TUIC_V5 -> "tuic"
            ProtocolType.TROJAN -> "trojan"
            ProtocolType.SHADOWSOCKS -> "shadowsocks"
            else -> return null
        }
        if (node.type == ProtocolType.VLESS_REALITY && node.realityPublicKey.isBlank()) return null
        if (type == "shadowsocks" && node.ssMethod.isBlank()) return null
        if (node.network.lowercase() !in setOf("", "tcp", "ws", "grpc")) return null
        return JsonObject().apply {
            addProperty("type", type); addProperty("tag", node.tag); addProperty("server", node.server)
            addProperty("server_port", node.serverPort)
            addProperty(if (type in setOf("vless", "vmess", "tuic")) "uuid" else "password", node.uuidOrPassword)
            if (type == "vless" && node.flow.isNotBlank()) addProperty("flow", node.flow)
            if (type == "vmess") addProperty("security", "auto")
            if (type == "shadowsocks") addProperty("method", node.ssMethod)
            if (type == "tuic") {
                if (node.extraPassword.isNotBlank()) addProperty("password", node.extraPassword)
                addProperty("congestion_control", "bbr"); addProperty("zero_rtt_handshake", true)
                addProperty("udp_relay_mode", "native")
            }
            if (type == "hysteria2") {
                if (node.hoppingPorts.isNotBlank()) {
                    remove("server_port")
                    add("server_ports", JsonArray().apply {
                        node.hoppingPorts.split(',').map(String::trim).filter(String::isNotEmpty)
                            .map { if ('-' in it && ':' !in it) it.replaceFirst('-', ':') else it }.forEach(::add)
                    })
                }
                if (node.obfs.isNotBlank()) add("obfs", JsonObject().apply {
                    addProperty("type", node.obfs)
                    if (node.obfsPassword.isNotBlank()) addProperty("password", node.obfsPassword)
                })
            }
            if (type in setOf("vless", "vmess", "trojan") && node.network.lowercase() in setOf("ws", "grpc")) {
                add("transport", JsonObject().apply {
                    addProperty("type", node.network.lowercase())
                    if (node.path.isNotBlank()) addProperty(if (node.network.equals("ws", true)) "path" else "service_name", node.path)
                    if (node.network.equals("ws", true) && node.host.isNotBlank()) add("headers", JsonObject().apply {
                        addProperty("Host", node.host)
                    })
                })
            }
            if (type != "shadowsocks" && (node.tlsEnabled || type in setOf("hysteria2", "tuic", "trojan") || node.type == ProtocolType.VLESS_REALITY)) {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true); addProperty("insecure", node.allowInsecure)
                    if (node.sni.isNotBlank()) addProperty("server_name", node.sni)
                    val alpn = node.alpn.ifBlank { if (type in setOf("hysteria2", "tuic")) "h3" else "" }
                    if (alpn.isNotBlank()) add("alpn", JsonArray().apply {
                        alpn.split(',').map(String::trim).filter(String::isNotEmpty).forEach(::add)
                    })
                    if (type == "vmess" || node.type == ProtocolType.VLESS_REALITY) add("utls", JsonObject().apply {
                        addProperty("enabled", true); addProperty("fingerprint", "chrome")
                    })
                    if (node.type == ProtocolType.VLESS_REALITY) add("reality", JsonObject().apply {
                        addProperty("enabled", true); addProperty("public_key", node.realityPublicKey)
                        if (node.realityShortId.isNotBlank()) addProperty("short_id", node.realityShortId)
                    })
                })
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private class Fields(source: JsonObject) {
        private val obj = source.deepCopy()
        fun string(key: String, default: String = ""): String = obj.remove(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
        } ?: default
        fun int(key: String, default: Int = -1): Int = obj.remove(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            it.asString.toInt()
        } ?: default
        fun bool(key: String, default: Boolean = false): Boolean = obj.remove(key)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
        } ?: default
        fun obj(key: String): JsonObject? = obj.remove(key)?.asJsonObject
        fun array(key: String): JsonArray? = obj.remove(key)?.asJsonArray
        fun finish() = require(obj.size() == 0)
    }
}

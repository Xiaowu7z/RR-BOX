package com.rr.client.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.yaml.snakeyaml.Yaml

/**
 * Converts the mainstream Clash/Mihomo `proxies:` section into sing-box outbounds.
 * The conversion deliberately covers node fields only; Clash routing/DNS is not imported.
 */
object ClashSubscriptionConverter {
    fun convert(rawYaml: String): String? {
        val root = runCatching { Yaml().load<Any>(rawYaml) }.getOrNull() as? Map<*, *> ?: return null
        val proxies = root["proxies"] as? List<*> ?: return null
        val outbounds = JsonArray()
        proxies.forEach { entry ->
            val proxy = entry as? Map<*, *> ?: return@forEach
            convertProxy(proxy)?.let(outbounds::add)
        }
        if (outbounds.size() == 0) return null
        return JsonObject().apply { add("outbounds", outbounds) }.toString()
    }

    private fun convertProxy(p: Map<*, *>): JsonObject? {
        val clashType = str(p, "type").lowercase()
        val server = str(p, "server")
        val port = int(p, "port")
        if (clashType.isBlank() || server.isBlank() || port !in 1..65535) return null

        val out = JsonObject().apply {
            addProperty("tag", str(p, "name").ifBlank { "$clashType-$server" })
            addProperty("server", server)
            addProperty("server_port", port)
        }

        when (clashType) {
            "ss", "shadowsocks" -> {
                out.addProperty("type", "shadowsocks")
                out.addProperty("method", str(p, "cipher"))
                out.addProperty("password", str(p, "password"))
            }

            "vmess" -> {
                out.addProperty("type", "vmess")
                out.addProperty("uuid", str(p, "uuid"))
                out.addProperty("security", str(p, "cipher").ifBlank { "auto" })
                addV2RayTransport(out, p)
                addTls(out, p, reality = false)
            }

            "vless" -> {
                out.addProperty("type", "vless")
                out.addProperty("uuid", str(p, "uuid"))
                str(p, "flow").takeIf(String::isNotBlank)?.let { out.addProperty("flow", it) }
                addV2RayTransport(out, p)
                addTls(out, p, reality = map(p, "reality-opts") != null || bool(p, "reality"))
            }

            "trojan" -> {
                out.addProperty("type", "trojan")
                out.addProperty("password", str(p, "password"))
                addV2RayTransport(out, p)
                addTls(out, p, reality = false, force = true)
            }

            "hysteria2", "hy2" -> {
                out.addProperty("type", "hysteria2")
                out.addProperty("password", str(p, "password").ifBlank { str(p, "auth") })
                val ports = str(p, "ports").ifBlank { str(p, "mport") }
                if (ports.isNotBlank()) {
                    out.remove("server_port")
                    out.add("server_ports", stringArray(ports))
                }
                val obfs = str(p, "obfs")
                val obfsPassword = str(p, "obfs-password").ifBlank { str(p, "obfs_password") }
                if (obfs.isNotBlank()) {
                    out.add("obfs", JsonObject().apply {
                        addProperty("type", obfs)
                        if (obfsPassword.isNotBlank()) addProperty("password", obfsPassword)
                    })
                }
                addTls(out, p, reality = false, force = true)
            }

            "hysteria" -> {
                out.addProperty("type", "hysteria")
                str(p, "auth-str").ifBlank { str(p, "auth_str") }.ifBlank { str(p, "auth") }
                    .takeIf(String::isNotBlank)?.let { out.addProperty("auth_str", it) }
                str(p, "obfs").takeIf(String::isNotBlank)?.let { out.addProperty("obfs", it) }
                int(p, "up").takeIf { it > 0 }?.let { out.addProperty("up_mbps", it) }
                int(p, "down").takeIf { it > 0 }?.let { out.addProperty("down_mbps", it) }
                addTls(out, p, reality = false, force = true)
            }

            "tuic" -> {
                out.addProperty("type", "tuic")
                out.addProperty("uuid", str(p, "uuid"))
                out.addProperty("password", str(p, "password"))
                str(p, "congestion-controller").ifBlank { str(p, "congestion_control") }
                    .takeIf(String::isNotBlank)?.let { out.addProperty("congestion_control", it) }
                out.addProperty("udp_relay_mode", "native")
                addTls(out, p, reality = false, force = true)
            }

            "socks5", "socks" -> {
                out.addProperty("type", "socks")
                out.addProperty("version", "5")
                str(p, "username").takeIf(String::isNotBlank)?.let { out.addProperty("username", it) }
                str(p, "password").takeIf(String::isNotBlank)?.let { out.addProperty("password", it) }
            }

            "http", "https" -> {
                out.addProperty("type", "http")
                str(p, "username").takeIf(String::isNotBlank)?.let { out.addProperty("username", it) }
                str(p, "password").takeIf(String::isNotBlank)?.let { out.addProperty("password", it) }
                if (clashType == "https" || bool(p, "tls")) addTls(out, p, reality = false, force = true)
            }

            "anytls" -> {
                out.addProperty("type", "anytls")
                out.addProperty("password", str(p, "password"))
                addTls(out, p, reality = false, force = true)
            }

            else -> return null
        }
        return out
    }

    private fun addV2RayTransport(out: JsonObject, p: Map<*, *>) {
        val network = str(p, "network").ifBlank { "tcp" }.lowercase()
        when (network) {
            "ws" -> {
                val options = map(p, "ws-opts")
                out.add("transport", JsonObject().apply {
                    addProperty("type", "ws")
                    str(options, "path").ifBlank { str(p, "ws-path") }
                        .takeIf(String::isNotBlank)?.let { addProperty("path", it) }
                    val headers = map(options, "headers")
                    val host = str(headers, "Host").ifBlank { str(headers, "host") }.ifBlank { str(p, "servername") }
                    if (host.isNotBlank()) add("headers", JsonObject().apply { addProperty("Host", host) })
                })
            }
            "grpc" -> {
                val options = map(p, "grpc-opts")
                val service = str(options, "grpc-service-name").ifBlank { str(p, "grpc-service-name") }
                out.add("transport", JsonObject().apply {
                    addProperty("type", "grpc")
                    if (service.isNotBlank()) addProperty("service_name", service)
                })
            }
            "http" -> {
                val options = map(p, "http-opts")
                val path = list(options, "path").firstOrNull()?.toString().orEmpty()
                out.add("transport", JsonObject().apply {
                    addProperty("type", "http")
                    if (path.isNotBlank()) addProperty("path", path)
                })
            }
        }
    }

    private fun addTls(
        out: JsonObject,
        p: Map<*, *>,
        reality: Boolean,
        force: Boolean = false
    ) {
        val enabled = force || reality || bool(p, "tls") || str(p, "security").equals("tls", true)
        if (!enabled) return
        val sni = str(p, "servername").ifBlank { str(p, "sni") }.ifBlank { str(p, "server") }
        out.add("tls", JsonObject().apply {
            addProperty("enabled", true)
            if (sni.isNotBlank()) addProperty("server_name", sni)
            if (bool(p, "skip-cert-verify")) addProperty("insecure", true)
            val alpn = list(p, "alpn").map(Any?::toString).filter(String::isNotBlank)
            if (alpn.isNotEmpty()) add("alpn", JsonArray().apply { alpn.forEach(::add) })
            if (reality) {
                val opts = map(p, "reality-opts")
                val publicKey = str(opts, "public-key").ifBlank { str(opts, "public_key") }
                val shortId = str(opts, "short-id").ifBlank { str(opts, "short_id") }
                if (publicKey.isNotBlank()) {
                    add("reality", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("public_key", publicKey)
                        if (shortId.isNotBlank()) addProperty("short_id", shortId)
                    })
                }
                str(p, "client-fingerprint").takeIf(String::isNotBlank)?.let { fp ->
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", fp)
                    })
                }
            }
        })
    }

    private fun stringArray(raw: String): JsonArray = JsonArray().apply {
        raw.split(',').map(String::trim).filter(String::isNotBlank)
            .map { if ('-' in it && ':' !in it) it.replaceFirst('-', ':') else it }
            .forEach(::add)
    }

    private fun map(source: Map<*, *>?, key: String): Map<*, *>? = source?.get(key) as? Map<*, *>
    private fun list(source: Map<*, *>?, key: String): List<*> = source?.get(key) as? List<*> ?: emptyList<Any>()
    private fun str(source: Map<*, *>?, key: String): String = source?.get(key)?.toString()?.trim().orEmpty()
    private fun int(source: Map<*, *>?, key: String): Int = when (val value = source?.get(key)) {
        is Number -> value.toInt()
        else -> value?.toString()?.toIntOrNull() ?: 0
    }
    private fun bool(source: Map<*, *>?, key: String): Boolean = when (val value = source?.get(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.equals("true", true) == true || value?.toString() == "1"
    }
}

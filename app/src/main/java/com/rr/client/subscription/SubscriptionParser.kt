package com.rr.client.subscription

import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.ProtocolType
import com.rr.client.subscription.model.SubscriptionUserInfo

object SubscriptionParser {
    private val gson = Gson()

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

    fun parseContent(rawContent: String): List<ProxyNode> {
        val trimmed = rawContent.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return parseSingBoxJson(trimmed)
        }

        val decoded = try {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            trimmed
        }

        val nodes = mutableListOf<ProxyNode>()
        decoded.lineSequence().forEach { line ->
            val node = parseUri(line.trim())
            if (node != null) nodes.add(node)
        }
        return nodes
    }

    private fun parseSingBoxJson(jsonStr: String): List<ProxyNode> {
        val list = mutableListOf<ProxyNode>()
        try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            if (root.has("outbounds")) {
                val outbounds = root.getAsJsonArray("outbounds")
                outbounds.forEachIndexed { index, el ->
                    val obj = el.asJsonObject
                    val type = obj.get("type")?.asString ?: ""
                    val tag = obj.get("tag")?.asString ?: "Node-$index"
                    val server = obj.get("server")?.asString ?: ""
                    val port = obj.get("server_port")?.asInt ?: 443

                    if (server.isNotEmpty() && !tag.contains("已用") && !tag.contains("剩余") && !tag.contains("到期")) {
                        val protocol = when (type) {
                            "vless" -> ProtocolType.VLESS_REALITY
                            "hysteria2" -> ProtocolType.HYSTERIA2
                            "tuic" -> ProtocolType.TUIC_V5
                            "vmess" -> ProtocolType.VMESS_WS_ARGO
                            "naive" -> ProtocolType.NAIVE_H2
                            else -> ProtocolType.CUSTOM
                        }
                        list.add(
                            ProxyNode(
                                id = "node_$index",
                                tag = tag,
                                type = protocol,
                                server = server,
                                serverPort = port,
                                uuidOrPassword = obj.get("uuid")?.asString ?: obj.get("password")?.asString ?: "",
                                rawJson = obj.toString() // Save exact JSON object from RRVPS!
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun parseUri(uriStr: String): ProxyNode? {
        if (uriStr.isBlank()) return null
        return try {
            val uri = Uri.parse(uriStr)
            when (uri.scheme) {
                "vless" -> {
                    val tag = Uri.decode(uri.fragment ?: "VLESS Node")
                    val userInfo = uri.userInfo ?: ""
                    val host = uri.host ?: ""
                    val port = if (uri.port > 0) uri.port else 443
                    val pbk = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: ""
                    val sid = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: ""
                    val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("serverName") ?: ""
                    val flow = uri.getQueryParameter("flow") ?: ""
                    ProxyNode(
                        id = "vless_${host}_$port",
                        tag = tag,
                        type = ProtocolType.VLESS_REALITY,
                        server = host,
                        serverPort = port,
                        uuidOrPassword = userInfo,
                        flow = flow,
                        realityPublicKey = pbk,
                        realityShortId = sid,
                        sni = sni
                    )
                }
                "hy2", "hysteria2" -> {
                    val tag = Uri.decode(uri.fragment ?: "Hysteria2 Node")
                    val host = uri.host ?: ""
                    val port = if (uri.port > 0) uri.port else 443
                    val password = uri.userInfo ?: ""
                    val sni = uri.getQueryParameter("sni") ?: ""
                    val ports = uri.getQueryParameter("ports") ?: ""
                    ProxyNode(
                        id = "hy2_${host}_$port",
                        tag = tag,
                        type = ProtocolType.HYSTERIA2,
                        server = host,
                        serverPort = port,
                        uuidOrPassword = password,
                        sni = sni,
                        hoppingPorts = ports
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}

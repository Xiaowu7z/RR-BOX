package com.rr.client.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode

/**
 * Applies user edits to the raw sing-box outbound without throwing away protocol-specific fields
 * that RRBOX does not model yet.
 */
object NodeOverridePatcher {
    fun resolve(base: ProxyNode, override: ProxyNode?): ProxyNode = when {
        override == null -> base
        override.nameOverrideOnly -> apply(base, base.copy(tag = override.tag))
        else -> override.copy(id = base.id, profileId = base.profileId, profileName = base.profileName)
    }

    fun isNameOnlyEdit(base: ProxyNode, override: ProxyNode): Boolean =
        apply(base, base.copy(tag = override.tag)) == override.copy(nameOverrideOnly = false)

    fun renameOverride(base: ProxyNode, override: ProxyNode?, newName: String): ProxyNode {
        require(newName.isNotBlank()) { "节点名称不能为空" }
        val current = resolve(base, override)
        return apply(current, current.copy(tag = newName.trim())).copy(
            nameOverrideOnly = override == null || override.nameOverrideOnly || isNameOnlyEdit(base, override)
        )
    }

    fun apply(original: ProxyNode, edited: ProxyNode): ProxyNode {
        // Raw advanced mode already reparses the outbound into a normalized ProxyNode. When the raw
        // payload changed, it is authoritative and must not be overwritten with patches from the
        // old outbound.
        if (edited.rawJson.isNotBlank() && edited.rawJson != original.rawJson) return edited
        if (original.rawJson.isBlank()) return edited.copy(rawJson = "")

        val outbound = runCatching {
            JsonParser.parseString(original.rawJson).asJsonObject.deepCopy()
        }.getOrNull() ?: return edited.copy(rawJson = original.rawJson)

        if (edited.tag != original.tag) outbound.addProperty("tag", edited.tag)
        if (edited.server != original.server) outbound.addProperty("server", edited.server)
        if (edited.serverPort != original.serverPort) outbound.addProperty("server_port", edited.serverPort)
        if (edited.flow != original.flow) setOrRemove(outbound, "flow", edited.flow)

        val type = outbound.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase().orEmpty()
        when (type) {
            "vless", "vmess", "tuic" -> {
                if (edited.uuidOrPassword != original.uuidOrPassword) {
                    outbound.addProperty("uuid", edited.uuidOrPassword)
                }
            }
            "hysteria2", "hy2", "trojan", "anytls", "naive", "shadowsocks", "ss", "socks", "http", "shadowtls" -> {
                if (edited.uuidOrPassword != original.uuidOrPassword) {
                    outbound.addProperty("password", edited.uuidOrPassword)
                }
            }
        }

        if (edited.uuidOrPassword != original.uuidOrPassword) {
            when (type) {
                "hysteria" -> outbound.addProperty("auth_str", edited.uuidOrPassword)
                "snell" -> outbound.addProperty("psk", edited.uuidOrPassword)
                "wireguard" -> outbound.addProperty("private_key", edited.uuidOrPassword)
                "ssh" -> outbound.addProperty(
                    if (outbound.has("private_key") && !outbound.has("password")) "private_key" else "password",
                    edited.uuidOrPassword
                )
            }
        }
        if (type in setOf("shadowsocks", "ss") && edited.ssMethod != original.ssMethod) {
            outbound.addProperty("method", edited.ssMethod)
        }

        if (edited.type == ProtocolType.TUIC_V5 && edited.extraPassword != original.extraPassword) {
            setOrRemove(outbound, "password", edited.extraPassword)
        }

        patchTls(outbound, original, edited)
        patchReality(outbound, original, edited)
        patchTransport(outbound, original, edited)
        patchHysteria(outbound, original, edited)

        return edited.copy(rawJson = outbound.toString())
    }

    private fun patchTls(outbound: JsonObject, original: ProxyNode, edited: ProxyNode) {
        val tlsRelevant = edited.sni != original.sni ||
            edited.alpn != original.alpn ||
            edited.tlsEnabled != original.tlsEnabled ||
            edited.allowInsecure != original.allowInsecure
        if (!tlsRelevant) return

        val tls = outbound.get("tls")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { outbound.add("tls", it) }
        tls.addProperty("enabled", edited.tlsEnabled)
        tls.addProperty("insecure", edited.allowInsecure)
        setOrRemove(tls, "server_name", edited.sni)

        if (edited.alpn.isBlank()) {
            tls.remove("alpn")
        } else {
            tls.add("alpn", JsonArray().apply {
                edited.alpn.split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(::add)
            })
        }
    }

    private fun patchReality(outbound: JsonObject, original: ProxyNode, edited: ProxyNode) {
        if (edited.realityPublicKey == original.realityPublicKey &&
            edited.realityShortId == original.realityShortId
        ) return

        val tls = outbound.get("tls")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { outbound.add("tls", it) }
        val reality = tls.get("reality")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { tls.add("reality", it) }
        reality.addProperty("enabled", true)
        setOrRemove(reality, "public_key", edited.realityPublicKey)
        setOrRemove(reality, "short_id", edited.realityShortId)
    }

    private fun patchTransport(outbound: JsonObject, original: ProxyNode, edited: ProxyNode) {
        if (edited.network == original.network && edited.path == original.path && edited.host == original.host) {
            return
        }

        if (edited.network.isBlank() || edited.network.equals("tcp", ignoreCase = true)) {
            outbound.remove("transport")
            return
        }

        val transport = outbound.get("transport")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { outbound.add("transport", it) }
        transport.addProperty("type", edited.network.lowercase())

        when (edited.network.lowercase()) {
            "ws" -> {
                setOrRemove(transport, "path", edited.path)
                transport.remove("service_name")
                if (edited.host.isBlank()) {
                    transport.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject?.remove("Host")
                } else {
                    val headers = transport.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?: JsonObject().also { transport.add("headers", it) }
                    headers.addProperty("Host", edited.host)
                }
            }
            "grpc" -> {
                setOrRemove(transport, "service_name", edited.path)
                transport.remove("path")
            }
        }
    }

    private fun patchHysteria(outbound: JsonObject, original: ProxyNode, edited: ProxyNode) {
        if (edited.type != ProtocolType.HYSTERIA2) return

        if (edited.hoppingPorts != original.hoppingPorts) {
            if (edited.hoppingPorts.isBlank()) {
                outbound.remove("server_ports")
                outbound.remove("ports")
                outbound.remove("mport")
                outbound.addProperty("server_port", edited.serverPort)
            } else {
                outbound.add("server_ports", JsonArray().apply {
                    edited.hoppingPorts.split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .map { if ('-' in it && ':' !in it) it.replaceFirst('-', ':') else it }
                        .forEach(::add)
                })
            }
        }

        if (edited.obfs != original.obfs || edited.obfsPassword != original.obfsPassword) {
            if (edited.obfs.isBlank()) {
                outbound.remove("obfs")
            } else {
                outbound.add("obfs", JsonObject().apply {
                    addProperty("type", edited.obfs)
                    if (edited.obfsPassword.isNotBlank()) addProperty("password", edited.obfsPassword)
                })
            }
        }
    }

    private fun setOrRemove(target: JsonObject, key: String, value: String) {
        if (value.isBlank()) target.remove(key) else target.addProperty(key, value)
    }
}

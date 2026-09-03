package com.rr.client.subscription

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Pure-Kotlin AnyTLS URI/Base64 fallback for subscription imports. */
object AnyTlsLinkParser {
    private val anyTlsRegex = Regex("(?i)anytls://[^\\s<>\\\"']+")

    fun extractFromContent(rawContent: String, profileId: String, profileName: String, startIndex: Int = 0): List<ProxyNode> {
        if (rawContent.isBlank()) return emptyList()
        val texts = linkedSetOf(rawContent.trim())
        decodeBase64Payload(rawContent)?.takeIf { it.contains("anytls://", ignoreCase = true) }?.let(texts::add)
        val links = linkedSetOf<String>()
        texts.forEach { text -> anyTlsRegex.findAll(text).forEach { links += it.value.trim() } }
        return links.mapIndexedNotNull { offset, link -> parseLink(link, profileId, profileName, startIndex + offset) }
    }

    fun parseLink(link: String, profileId: String, profileName: String, index: Int = 0): ProxyNode? {
        val value = link.trim()
        if (!value.startsWith("anytls://", ignoreCase = true)) return null
        val body = value.substringAfter("://")
        val fragmentRaw = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val queryRaw = beforeFragment.substringAfter('?', "")
        val authority = beforeFragment.substringBefore('?').substringBefore('/')
        val at = authority.lastIndexOf('@')
        val credentialRaw = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val endpoint = parseEndpoint(hostPort) ?: return null
        val params = parseQuery(queryRaw)
        val password = percentDecode(credentialRaw).ifBlank { params["password"].orEmpty() }
        if (password.isBlank()) return null
        val sni = params["sni"].orEmpty().ifBlank { params["peer"].orEmpty() }.ifBlank { params["servername"].orEmpty() }.ifBlank { endpoint.host }
        val insecure = queryBoolean(params, "insecure") || queryBoolean(params, "allowinsecure") || queryBoolean(params, "skip-cert-verify")
        val alpnValues = params["alpn"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        val tag = percentDecode(fragmentRaw).ifBlank { "AnyTLS-${endpoint.host}" }

        val outbound = JsonObject().apply {
            addProperty("type", "anytls")
            addProperty("tag", tag)
            addProperty("server", endpoint.host)
            addProperty("server_port", endpoint.port)
            addProperty("password", password)
            params["idle_session_check_interval"]?.takeIf(String::isNotBlank)?.let { addProperty("idle_session_check_interval", it) }
            params["idle_session_timeout"]?.takeIf(String::isNotBlank)?.let { addProperty("idle_session_timeout", it) }
            params["min_idle_session"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { addProperty("min_idle_session", it) }
            params["client_metadata"]?.takeIf(String::isNotBlank)?.let { addProperty("client_metadata", it) }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", sni)
                if (insecure) addProperty("insecure", true)
                if (alpnValues.isNotEmpty()) add("alpn", JsonArray().apply { alpnValues.forEach(::add) })
            })
        }
        return ProxyNode(
            id = "$profileId-${endpoint.host.replace(':', '_')}_${endpoint.port}-$index",
            tag = tag,
            type = ProtocolType.ANYTLS,
            server = endpoint.host,
            serverPort = endpoint.port,
            uuidOrPassword = password,
            sni = sni,
            alpn = alpnValues.joinToString(","),
            tlsEnabled = true,
            profileId = profileId,
            profileName = profileName,
            rawJson = outbound.toString()
        )
    }

    private data class Endpoint(val host: String, val port: Int)

    private fun parseEndpoint(raw: String): Endpoint? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close <= 1) return null
            val host = value.substring(1, close)
            val suffix = value.substring(close + 1)
            val port = if (suffix.startsWith(':')) suffix.substring(1).toIntOrNull() ?: return null else 443
            if (port !in 1..65535) return null
            return Endpoint(percentDecode(host), port)
        }
        val lastColon = value.lastIndexOf(':')
        if (lastColon > 0) {
            val possiblePort = value.substring(lastColon + 1).toIntOrNull()
            if (possiblePort != null) {
                if (possiblePort !in 1..65535) return null
                val host = value.substring(0, lastColon)
                if (host.isBlank()) return null
                return Endpoint(percentDecode(host), possiblePort)
            }
        }
        return Endpoint(percentDecode(value), 443)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = percentDecode(pair.substringBefore('=')).trim().lowercase()
            if (key.isNotBlank()) result[key] = percentDecode(pair.substringAfter('=', ""))
        }
        return result
    }

    private fun queryBoolean(params: Map<String, String>, key: String): Boolean = when (params[key]?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

    private fun percentDecode(value: String): String = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun decodeBase64Payload(raw: String): String? {
        val compact = raw.filterNot(Char::isWhitespace)
        if (compact.length < 8) return null
        val padded = compact.padEnd((compact.length + 3) / 4 * 4, '=')
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder(), Base64.getMimeDecoder())
        decoders.forEach { decoder ->
            val decoded = runCatching { String(decoder.decode(padded), StandardCharsets.UTF_8) }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.contains("://")) return decoded
        }
        return null
    }
}

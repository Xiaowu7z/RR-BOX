package com.rr.client.subscription

import java.net.URI

/** URL classification only; never performs DNS/network requests or decodes tokens. */
object SubscriptionUrlNormalizer {
    fun clean(raw: String): String = raw.trim().removePrefix("\uFEFF").trim()

    fun candidates(raw: String): List<String> {
        val input = clean(raw)
        require(input.isNotEmpty()) { "订阅地址不能为空" }
        require(httpUri(input) != null) { "请输入有效的 HTTP/HTTPS 订阅地址（IPv6 请使用方括号）" }
        return when {
            input.startsWith("https://", true) || input.startsWith("http://", true) -> listOf(input)
            else -> {
                val body = input.removePrefix("//")
                listOf("https://$body", "http://$body")
            }
        }
    }

    fun looksLikeSubscriptionAddress(raw: String): Boolean {
        val uri = httpUri(clean(raw)) ?: return false
        return uri.rawUserInfo == null && hasResource(uri)
    }

    /** A root HTTP URL can be a subscription or proxy. Ask instead of silently guessing. */
    fun isAmbiguousHttpAddress(raw: String): Boolean {
        val uri = httpUri(clean(raw)) ?: return false
        return (uri.rawUserInfo == null && !hasResource(uri)) ||
            (uri.rawUserInfo != null && hasResource(uri))
    }

    private fun hasResource(uri: URI): Boolean =
        (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") || uri.rawQuery != null

    private fun httpUri(input: String): URI? {
        if (input.isEmpty() || input.any { it.isWhitespace() || it.isISOControl() }) return null
        val explicit = SCHEME_REGEX.containsMatchIn(input)
        val candidate = if (explicit) input else "https://${input.removePrefix("//")}"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
        val host = uri.host ?: return null
        if (host.isBlank() || uri.port !in -1..65535 || uri.port == 0) return null
        if (!explicit && !host.contains('.') && !host.contains(':') && !host.equals("localhost", true)) return null
        return uri
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}

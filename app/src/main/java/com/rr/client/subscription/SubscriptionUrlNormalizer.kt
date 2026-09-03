package com.rr.client.subscription

/**
 * Accept the forms users commonly paste from panels:
 *   https://example.com/sub
 *   http://1.2.3.4:8080/sub
 *   1.2.3.4:8080/sub
 *   example.com/sub
 *   [2001:db8::1]:8080/sub
 *
 * Scheme-less input tries HTTPS first and HTTP second. The HTTP fallback is
 * intentional for private/self-hosted panels that expose subscriptions by IP.
 */
object SubscriptionUrlNormalizer {
    fun candidates(raw: String): List<String> {
        val input = raw.trim()
        require(input.isNotEmpty()) { "订阅地址不能为空" }
        require(!input.contains('\n') && !input.contains('\r')) { "订阅地址不能包含换行" }

        val lower = input.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return listOf(input)
        }
        if (input.startsWith("//")) {
            val body = input.removePrefix("//")
            return listOf("https://$body", "http://$body")
        }
        if (SCHEME_REGEX.containsMatchIn(input)) {
            throw IllegalArgumentException("订阅地址只支持 HTTP/HTTPS")
        }

        return listOf("https://$input", "http://$input")
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}

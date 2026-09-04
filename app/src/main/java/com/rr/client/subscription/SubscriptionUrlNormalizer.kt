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

    fun looksLikeSubscriptionAddress(raw: String): Boolean {
        val input = raw.trim()
        if (input.isEmpty() || input.contains('\n') || input.contains('\r')) return false

        val lower = input.lowercase()
        if (SCHEME_REGEX.containsMatchIn(input) &&
            !lower.startsWith("https://") &&
            !lower.startsWith("http://")
        ) return false

        val body = when {
            lower.startsWith("https://") -> input.substring(8)
            lower.startsWith("http://") -> input.substring(7)
            input.startsWith("//") -> input.substring(2)
            else -> input
        }
        val authority = body.substringBefore('/').substringBefore('?')
        if (authority.isBlank() || authority.any(Char::isWhitespace) || '@' in authority) return false

        val validHost = if (authority.startsWith("[") && authority.contains("]")) {
            true
        } else {
            val host = authority.substringBefore(':')
            host.equals("localhost", ignoreCase = true) || host.contains('.')
        }
        if (!validHost) return false

        val remainder = body.removePrefix(authority)
        return remainder.isNotBlank() && remainder != "/"
    }

    private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
}

package com.rr.client.subscription

import com.rr.client.core.model.ProxyNode

/** A display-only view of an explicitly labelled subscription information entry. */
data class TrafficInfoDisplay(
    /** Always retain the exact source, including unknown fields and punctuation. */
    val originalText: String,
    val detailText: String,
    val usedText: String? = null,
    val remainingText: String? = null,
    val totalText: String? = null,
    val expiryText: String? = null
)

/**
 * Recognizes only an explicit "do not select" information title on a discard
 * endpoint. A normal node mentioning traffic or expiry is still a normal node.
 *
 * Values are display text, not normalized numbers. Units, dates, unlimited
 * allowances and unknown provider fields remain exactly as supplied; missing
 * values are never inferred from other fields.
 */
object TrafficInfoNode {
    private val title = Regex(
        "^\\s*(?:流量信息|订阅流量|流量统计)\\s*[（(]\\s*" +
            "(?:勿选|请勿选|请勿选择|勿连接|请勿连接|仅供查看)\\s*[）)]" +
            "(?=$|[\\s|｜:：])"
    )
    private val field = Regex(
        "^\\s*(已使用流量|已使用|已用流量|已用|剩余流量|剩余|" +
            "总流量|流量总量|总量|到期时间|到期日期|到期|有效期至)\\s*[:：]?\\s*(.+)$"
    )
    private val separators = Regex("[|｜;；\\r\\n]")

    fun isInfoNode(node: ProxyNode): Boolean =
        node.serverPort == 9 && isPlaceholder(node.server) && title.containsMatchIn(node.tag)

    fun parse(node: ProxyNode): TrafficInfoDisplay? {
        if (!isInfoNode(node)) return null
        val marker = title.find(node.tag) ?: return null
        val detail = node.tag.substring(marker.range.last + 1)
            .trimStart { it.isWhitespace() || it in "|｜:：" }
        val values = mutableMapOf<String, MutableList<String>>()
        detail.split(separators).forEach { segment ->
            val match = field.matchEntire(segment) ?: return@forEach
            val value = match.groupValues[2].trim().takeIf(String::isNotEmpty) ?: return@forEach
            val key = when (match.groupValues[1]) {
                "已使用流量", "已使用", "已用流量", "已用" -> "used"
                "剩余流量", "剩余" -> "remaining"
                "总流量", "流量总量", "总量" -> "total"
                else -> "expiry"
            }
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        // Conflicting duplicate labels have no authoritative single value.
        fun value(key: String): String? = values[key]?.distinct()?.singleOrNull()
        return TrafficInfoDisplay(
            originalText = node.tag,
            detailText = detail,
            usedText = value("used"),
            remainingText = value("remaining"),
            totalText = value("total"),
            expiryText = value("expiry")
        )
    }

    private fun isPlaceholder(server: String): Boolean {
        val host = server.trim().removePrefix("[").removeSuffix("]")
        if (host.equals("localhost", ignoreCase = true) || host == "::1" ||
            host == "0:0:0:0:0:0:0:1" || host == "0.0.0.0") return true
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "127" && octets.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

package com.rr.client.lab

import com.google.gson.JsonParser
import com.rr.client.security.SecretRedactor
import java.util.Locale

/** Values come from libbox ConnectionEvents, never from inferred log-message matches. */
data class ConnectionRouteRecord(
    val application: String?,
    val packages: List<String>,
    val uid: Int?,
    val domain: String,
    val destination: String,
    val network: String,
    val outbound: String,
    val outboundType: String,
    val rule: String,
    val hev: Boolean
)

data class ConnectionRouteMessage(val timestamp: Long, val message: String)

object ConnectionRouteLog {
    const val CHANNEL = "ROUTE"

    /** A malformed/quiet configuration must never accidentally enable diagnostics. */
    fun enabledForConfig(configJson: String): Boolean = runCatching {
        val log = JsonParser.parseString(configJson).asJsonObject.getAsJsonObject("log")
            ?: return@runCatching false
        log.get("disabled")?.asBoolean != true &&
            log.get("level")?.asString?.lowercase(Locale.ROOT) in setOf("trace", "debug", "info")
    }.getOrDefault(false)

    fun format(record: ConnectionRouteRecord): String {
        val application = when {
            record.hev -> "未知应用（HEV 未提供原始应用身份）"
            record.packages.isNotEmpty() -> buildString {
                record.application?.takeIf(String::isNotBlank)?.let { append(it).append(" ") }
                append("(").append(record.packages.joinToString(", ")).append(")")
            }
            record.uid != null && record.uid >= 0 -> "未知应用（UID ${record.uid}）"
            else -> "未知应用"
        }
        val domain = endpointOnly(record.domain)
        val destination = endpointOnly(record.destination)
        val target = when {
            domain.isBlank() -> destination.ifBlank { "未知目标" }
            destination.isBlank() || destination == domain -> domain
            else -> "$domain [$destination]"
        }
        val outbound = when (record.outboundType.lowercase(Locale.ROOT)) {
            "direct" -> "直连"
            "block" -> "阻断"
            "", "selector", "urltest" -> "出口"
            else -> "代理"
        }
        return SecretRedactor.redact(buildString {
            append(record.network.uppercase(Locale.ROOT).ifBlank { "未知协议" })
            append(" · ").append(application)
            append(" → ").append(target)
            append(" → ").append(outbound)
            append(" (").append(record.outbound.ifBlank { "未知" }.take(160))
            record.outboundType.takeIf(String::isNotBlank)?.let { append(" / ").append(it.take(40)) }
            append(")")
            if (record.rule.isNotBlank()) append("\n命中：").append(record.rule.take(1024))
        })
    }

    /** Keep the endpoint useful for tests, but never retain URI credentials/path/query/fragment. */
    internal fun endpointOnly(raw: String): String = raw.trim().take(2048)
        .substringAfter("://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
        .filterNot { it.isISOControl() }
        .take(300)
}

/** Bounded ID history keeps connection updates/reconnect snapshots out of the diagnostic log. */
internal class ConnectionLogDeduplicator(private val capacity: Int = 4096) {
    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun accept(id: String): Boolean {
        if (id.isBlank() || !ids.add(id)) return false
        if (ids.size > capacity) ids.iterator().run { next(); remove() }
        return true
    }

    @Synchronized
    fun clear() = ids.clear()
}

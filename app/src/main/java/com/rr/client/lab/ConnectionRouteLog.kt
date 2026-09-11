package com.rr.client.lab

import com.google.gson.JsonParser
import java.util.Locale
import java.security.MessageDigest
import java.util.UUID

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
    val hev: Boolean,
    val inbound: String = ""
)

data class ConnectionRouteMessage(val timestamp: Long, val message: String)

/** Captured from the config actually started by libbox, never from the selected UI preference. */
internal class ConnectionLogRuntime private constructor(
    val engine: String, val tunStack: String?, val routing: AppRoutingDiagnostics.RuntimeSnapshot?
) {
    val summary: String get() = "核心引擎：$engine" + (tunStack?.let { "；TUN 栈：$it" } ?: "")
    val entrance: String get() = when (engine) {
        "ROOT", "SYSTEM" -> "$engine / TUN（${tunStack ?: "未提供"} 栈）"
        "HEV" -> "HEV / SOCKS"
        else -> "未知引擎 / 其他"
    }

    companion object {
        fun fromConfig(configJson: String, rootAttached: Boolean, hevSocksTag: String,
            routing: AppRoutingDiagnostics.RuntimeSnapshot? = null): ConnectionLogRuntime {
            val inbounds = runCatching {
                JsonParser.parseString(configJson).asJsonObject.getAsJsonArray("inbounds")
                    ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
            }.getOrDefault(emptyList())
            val tun = inbounds.firstOrNull { runCatching { it.get("type")?.asString == "tun" }.getOrDefault(false) }
            // Only known stack names are persisted; arbitrary configuration values may contain secrets.
            val stack = tun?.let { runCatching { it.get("stack")?.asString }.getOrNull() }
                ?.takeIf { it in setOf("system", "gvisor", "mixed") }
            val engine = when {
                rootAttached -> "ROOT"
                tun != null -> "SYSTEM"
                inbounds.any { inbound -> runCatching {
                    inbound.get("type")?.asString == "socks" && inbound.get("tag")?.asString == hevSocksTag
                }.getOrDefault(false) } -> "HEV"
                else -> "UNKNOWN"
            }
            return ConnectionLogRuntime(engine, if (engine in setOf("ROOT", "SYSTEM")) stack ?: "未提供" else null, routing)
        }
    }
}

object ConnectionRouteLog {
    const val CHANNEL = "ROUTE"

    /** A malformed/quiet configuration must never accidentally enable diagnostics. */
    fun enabledForConfig(configJson: String): Boolean = runCatching {
        val log = JsonParser.parseString(configJson).asJsonObject.getAsJsonObject("log")
            ?: return@runCatching false
        log.get("disabled")?.asBoolean != true &&
            log.get("level")?.asString?.lowercase(Locale.ROOT) in setOf("trace", "debug", "info")
    }.getOrDefault(false)

    fun format(record: ConnectionRouteRecord): String = format(record, null)

    internal fun format(record: ConnectionRouteRecord, runtime: ConnectionLogRuntime?): String {
        val application = when {
            record.hev -> "未知应用（HEV 未提供原始应用身份）"
            record.packages.isNotEmpty() -> buildString {
                record.application?.takeIf(String::isNotBlank)?.let { append(it).append(" ") }
                append("(").append(record.packages.take(16).joinToString(", ") { it.take(160) }.take(1000)).append(")")
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
        return AppRoutingDiagnostics.safeText(buildString {
            append(record.network.uppercase(Locale.ROOT).ifBlank { "未知协议" })
            append(" · ").append(application)
            append(" → ").append(target)
            append(" → ").append(outbound)
            append(" (").append(AppRoutingDiagnostics.outletKey(record.outbound))
            record.outboundType.takeIf(String::isNotBlank)?.let { append(" / ").append(it.take(40)) }
            append(")")
            append("\n入口：").append(runtime?.entrance ?: if (record.hev) "HEV / SOCKS" else "TUN / 其他")
            if (record.hev && record.inbound.matches(Regex("hev-app-in-[0-9]+"))) {
                append("；专用入口=").append(record.inbound)
                runtime?.routing?.entrancePackages?.get(record.inbound)?.takeIf { it.isNotEmpty() }?.let { names ->
                    append("\n绑定应用候选：").append(names.take(16).joinToString(", "))
                    if (names.size > 16) append(" 等 ").append(names.size).append(" 个")
                    append("（来自当前入口映射；核心未提供原始 UID，未确认实际进程）")
                }
            }
            runtime?.routing?.let { append("\n运行关联：").append(it.context) }
            if (isSharedIpv4Target(destination)) {
                append("；共享地址目标；可能为旧映射或运营商地址，尚未确认")
            }
            if (record.rule.isNotBlank()) {
                append("\n命中：").append(AppRoutingDiagnostics.safeText(record.rule).take(1024))
                append("\n规则摘要 ID：").append(diagnosticId(record.rule))
            } else {
                append("\n命中：核心未提供具体规则（可能使用默认出口）")
            }
        })
    }

    /** Strict numeric parsing only: never perform DNS lookups while formatting diagnostics. */
    internal fun isSharedIpv4Target(endpoint: String): Boolean {
        val parts = endpoint.split(':')
        if (parts.size !in 1..2) return false
        if (parts.size == 2 && (parts[1].isEmpty() || parts[1].any { it !in '0'..'9' } ||
                parts[1].toIntOrNull() !in 0..65535)) return false
        val octets = parts[0].split('.')
        if (octets.size != 4) return false
        val values = octets.map { value ->
            if (value.isEmpty() || value.length > 3 || value.any { it !in '0'..'9' } ||
                (value.length > 1 && value.startsWith('0'))) return false
            value.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return values[0] == 100 && values[1] in 64..127
    }

    /** Correlation labels are hashes, never credentials or native UUIDs redacted by the log store. */
    internal fun diagnosticId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    /** Keep the endpoint useful for tests, but never retain URI credentials/path/query/fragment. */
    internal fun endpointOnly(raw: String): String = raw.trim().take(2048)
        .substringAfter("://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')
        .filterNot { it.isISOControl() }
        .take(300)
}

/** Snapshot values only; traffic deltas are deliberately excluded from persistent diagnostics. */
internal data class ConnectionLogObservation(
    val id: String,
    val record: ConnectionRouteRecord?,
    val createdAt: Long = 0,
    val closedAt: Long = 0,
    val uplinkTotal: Long? = null,
    val downlinkTotal: Long? = null,
    val closed: Boolean = false,
    val snapshot: Boolean = false
)

/**
 * At most one observation and one end record per retained ID. The upstream initial snapshot uses
 * NEW even for already closed connections, which must be logged as history, never as a success.
 * Only bounded, redacted route summaries are cached; no packet data or ongoing deltas are retained.
 */
internal class ConnectionLogTracker(private val capacity: Int = 4096) {
    init { require(capacity > 0) }
    private data class Seen(val ended: Boolean, val route: String, val createdAt: Long)
    private val connections = LinkedHashMap<String, Seen>()
    private var runtime: ConnectionLogRuntime? = null
    @Volatile var sessionId: String = newSessionId()
        private set

    @Synchronized
    fun sessionStartedMessage(configJson: String, rootAttached: Boolean, hevSocksTag: String,
        routing: AppRoutingDiagnostics.RuntimeSnapshot? = null): String {
        val context = ConnectionLogRuntime.fromConfig(configJson, rootAttached, hevSocksTag, routing)
        runtime = context
        return "详细采集开始；会话：$sessionId；${context.summary}；sing-box 1.14.0；" +
            "核心已启动，数据面就绪以启动结果为准；目标为核心记录的元数据，未提供最终拨号 IP。"
    }

    @Synchronized
    fun sessionStoppedMessage(): String = "详细采集停止；会话：$sessionId；" +
        (runtime?.summary?.let { "$it；" } ?: "") + "未收到结束事件的连接结果未知。"

    @Synchronized
    fun format(event: ConnectionLogObservation, now: Long, expectedSessionId: String = sessionId): ConnectionRouteMessage? {
        if (expectedSessionId != sessionId || event.id.isBlank()) return null
        val previous = connections[event.id]
        val ended = event.closed || event.closedAt > 0
        if (!ended && event.record == null) return null
        if (previous != null && (!ended || previous.ended)) return null
        val route = event.record?.let { ConnectionRouteLog.format(it, runtime) }?.take(2800)
            ?: previous?.route ?: "目标及路由：核心未提供（无法补全）"
        val createdAt = event.createdAt.takeIf { it > 0 } ?: previous?.createdAt ?: 0
        // Ended IDs retain only a small tombstone; their route cannot be needed again.
        connections[event.id] = Seen(ended, if (ended) "" else route, createdAt)
        if (connections.size > capacity) connections.entries.iterator().run { next(); remove() }
        return ConnectionRouteMessage(
            timestamp = if (ended) event.closedAt.takeIf { it > 0 } ?: now else createdAt.takeIf { it > 0 } ?: now,
            message = buildString {
                append(route)
                append("\n会话：").append(sessionId).append("；连接：").append(ConnectionRouteLog.diagnosticId(event.id))
                append("\n状态：")
                when {
                    ended && event.snapshot -> append("已结束（历史快照）")
                    ended -> append("已结束（核心事件）")
                    event.snapshot -> append("活跃连接快照（未确认拨号成功）")
                    else -> append("已记录路由（未确认拨号成功）")
                }
                append("\n核心计数：上行 ").append(byteCount(event.uplinkTotal))
                append("；下行 ").append(byteCount(event.downlinkTotal))
                if (ended) {
                    append("；历时 ")
                    if (createdAt > 0 && event.closedAt >= createdAt) append(event.closedAt - createdAt).append(" ms")
                    else append("未知")
                    append("\n结束原因：核心连接 API 未提供；收发计数不等于业务成功，可结合 CORE 错误日志判断。")
                }
            }
        )
    }

    @Synchronized
    fun clear() {
        connections.clear()
        runtime = null
        sessionId = newSessionId()
    }

    private fun byteCount(value: Long?): String = value?.takeIf { it >= 0 }?.let { "$it B" } ?: "未知"
    private fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}

/** sing-box 1.14 log levels: panic=0, fatal=1, error=2, warn=3, info=4, debug=5, trace=6. */
internal object CoreDiagnosticLog {
    fun format(level: Int, raw: String, sessionId: String): String? {
        val label = when (level) { 0 -> "PANIC"; 1 -> "FATAL"; 2 -> "ERROR"; 3 -> "WARN"; else -> return null }
        val safeMessage = AppRoutingDiagnostics.safeText(raw).take(3500).trim()
        if (safeMessage.isEmpty()) return null
        return "[$label] $safeMessage\n会话：$sessionId；时间为接收时间，可能含核心缓冲补录；未与连接 ID 强行关联。"
    }
}

package com.rr.client.lab

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object RRLogStore {
    private const val MAX_LINES = 600
    private const val MAX_MESSAGE_LENGTH = 4096
    private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingRoutes = ArrayDeque<LabLogEntry>()
    private var pendingDropped = 0
    private var publishJob: Job? = null
    private val _connectionLoggingActive = MutableStateFlow(false)
    val connectionLoggingActive: StateFlow<Boolean> = _connectionLoggingActive.asStateFlow()
    private val _entries = MutableStateFlow<List<LabLogEntry>>(emptyList())
    val entries: StateFlow<List<LabLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(channel: String, message: String) {
        if (channel == ConnectionRouteLog.CHANNEL && !_connectionLoggingActive.value) return
        val cleaned = redact(message.take(MAX_MESSAGE_LENGTH)).trim()
        if (cleaned.isBlank()) return
        val next = _entries.value + LabLogEntry(channel = channel, message = cleaned)
        _entries.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }

    @Synchronized
    fun setConnectionLoggingActive(active: Boolean) {
        _connectionLoggingActive.value = active
        if (!active) {
            publishJob?.cancel()
            publishJob = null
            pendingRoutes.clear()
            pendingDropped = 0
        }
    }

    /** No disk writes and no per-packet logs. Publish at most twice per second to Compose. */
    @Synchronized
    fun recordConnections(messages: List<ConnectionRouteMessage>) {
        if (!_connectionLoggingActive.value || messages.isEmpty()) return
        messages.forEach { message ->
            val cleaned = redact(message.message.take(MAX_MESSAGE_LENGTH)).trim()
            if (cleaned.isNotBlank()) {
                if (pendingRoutes.size >= MAX_LINES) {
                    pendingRoutes.removeFirst()
                    pendingDropped++
                }
                pendingRoutes.addLast(LabLogEntry(
                    timestamp = message.timestamp,
                    channel = ConnectionRouteLog.CHANNEL,
                    message = cleaned
                ))
            }
        }
        if (publishJob?.isActive != true) {
            publishJob = routeScope.launch {
                delay(500)
                publishConnections()
            }
        }
    }

    @Synchronized
    private fun publishConnections() {
        if (_connectionLoggingActive.value && pendingRoutes.isNotEmpty()) {
            val batch = pendingRoutes.toMutableList()
            if (pendingDropped > 0) batch.add(LabLogEntry(
                channel = ConnectionRouteLog.CHANNEL,
                message = "连接突发过多，本批省略 $pendingDropped 条较早记录；保留最近 $MAX_LINES 条。"
            ))
            _entries.value = (_entries.value + batch).sortedBy { it.timestamp }.takeLast(MAX_LINES)
        }
        pendingRoutes.clear()
        pendingDropped = 0
        publishJob = null
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        pendingRoutes.clear()
        pendingDropped = 0
    }

    fun exportText(selectedEntries: List<LabLogEntry> = entries.value): String = buildString {
        appendLine("RRBOX 日志中心（已自动脱敏）")
        selectedEntries.forEach { entry ->
            appendLine("${entry.timestamp}\t${entry.channel}\t${entry.message}")
        }
    }

    internal fun redact(input: String): String = com.rr.client.security.SecretRedactor.redact(input)

}

object RRLogCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: java.lang.Process? = null
    private var lightweightMode: Boolean? = null

    @Synchronized
    fun start(lightweight: Boolean = false) {
        if (job?.isActive == true && lightweightMode == lightweight) return
        stop()
        lightweightMode = lightweight
        job = scope.launch {
            runCatching {
                val p = ProcessBuilder(
                    "/system/bin/logcat",
                    "--pid=${Process.myPid()}",
                    "-v",
                    "brief",
                    if (lightweight) "*:W" else "*:D"
                )
                    .redirectErrorStream(true)
                    .start()
                synchronized(RRLogCapture) {
                    if (!isActive) {
                        p.destroy()
                        return@launch
                    }
                    process = p
                }
                BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        val channel = when {
                            line.contains("HEV", ignoreCase = true) -> "HEV"
                            line.contains("RRVpnService", ignoreCase = true) ||
                                line.contains("libbox", ignoreCase = true) -> "CORE"
                            else -> "APP"
                        }
                        RRLogStore.record(channel, line)
                    }
                }
            }.onFailure { error ->
                if (isActive) RRLogStore.record("LOG", "应用日志采集不可用: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
        lightweightMode = null
    }
}

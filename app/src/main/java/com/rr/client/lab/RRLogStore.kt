package com.rr.client.lab

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object RRLogStore {
    private const val MAX_LINES = 600
    private val _entries = MutableStateFlow<List<LabLogEntry>>(emptyList())
    val entries: StateFlow<List<LabLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(channel: String, message: String) {
        val cleaned = redact(message).trim()
        if (cleaned.isBlank()) return
        val next = _entries.value + LabLogEntry(channel = channel, message = cleaned)
        _entries.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun exportText(): String = buildString {
        appendLine("RRBOX 日志中心（已自动脱敏）")
        entries.value.forEach { entry ->
            appendLine("${entry.timestamp}\t${entry.channel}\t${entry.message}")
        }
    }

    internal fun redact(input: String): String = com.rr.client.security.SecretRedactor.redact(input)

}

object RRLogCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: java.lang.Process? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching {
                val p = ProcessBuilder(
                    "/system/bin/logcat",
                    "--pid=${Process.myPid()}",
                    "-v",
                    "brief"
                )
                    .redirectErrorStream(true)
                    .start()
                process = p
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
                RRLogStore.record("LOG", "应用日志采集不可用: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
    }
}

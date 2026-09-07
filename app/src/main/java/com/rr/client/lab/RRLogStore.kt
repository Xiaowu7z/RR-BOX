package com.rr.client.lab

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object RRLogStore {
    private val store = PersistentLogStore()
    val state = store.state
    /** Compatibility cache only: use query/export for the complete retained history. */
    val entries = store.entries
    val connectionLoggingActive = store.connectionLoggingActive

    fun initialize(context: android.content.Context) {
        val app = context.applicationContext
        store.initialize(
            factory = { SqliteLogStorage(java.io.File(app.noBackupFilesDir, "log-history")) },
            stagingDirectory = { java.io.File(app.noBackupFilesDir, "log-history/exports") }
        )
    }

    fun record(channel: String, message: String) = store.record(channel, message)
    fun recordConnections(messages: List<ConnectionRouteMessage>) = store.recordConnections(messages)
    fun setConnectionLoggingActive(active: Boolean) = store.setConnectionLoggingActive(active)

    suspend fun query(
        filter: RRLogFilter = RRLogFilter(),
        beforeId: Long? = null,
        limit: Int = 200
    ): RRLogPage = store.query(filter, beforeId, limit)

    suspend fun export(output: java.io.OutputStream, filter: RRLogFilter = RRLogFilter()): Long =
        store.export(output, filter)

    suspend fun setRetentionLimit(limit: Int) = store.setRetentionLimit(limit)
    suspend fun clearHistory() = store.clearHistory()
    fun clear() { store.clear() }

    /** Small selection copy retained for callers; TXT export uses the complete persistent snapshot. */
    fun exportText(selectedEntries: List<LabLogEntry> = entries.value): String = buildString {
        appendLine("RRBOX 日志中心（已自动脱敏）")
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", java.util.Locale.ROOT)
        selectedEntries.forEach { entry ->
            appendLine("${date.format(java.util.Date(entry.timestamp))}\t${entry.timestamp}\t${redact(entry.channel)}\t${redact(entry.message)}")
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

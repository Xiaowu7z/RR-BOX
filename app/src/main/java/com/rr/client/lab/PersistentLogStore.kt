package com.rr.client.lab

import com.rr.client.security.SecretRedactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/** Non-blocking bounded intake; one ordered worker owns all database access. */
internal class PersistentLogStore(
    private val flushDelayMillis: Long = 500,
    private val pendingCapacity: Int = 2048,
    private val recentCapacity: Int = 600
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val requestSlots = Semaphore(32)
    private val pending = ArrayDeque<Pending>()
    private val requests = ArrayDeque<Request>()
    private var sequence = 0L
    private var historyGeneration = 0L
    private var connectionGeneration = 0L
    private var droppedRoutes = 0L
    private var droppedOther = 0L
    private var flushJob: Job? = null
    private var initializationRequested = false
    private var snapshotDirectory: File? = null
    private var storage: LogStorage = MemoryLogStorage()
    private var diskUnavailable = false
    private var recent = emptyList<RRStoredLogEntry>()
    private val mutableState = MutableStateFlow(RRLogState())
    val state = mutableState.asStateFlow()
    private val mutableEntries = MutableStateFlow<List<LabLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val mutableConnectionActive = MutableStateFlow(false)
    val connectionLoggingActive = mutableConnectionActive.asStateFlow()

    private data class Pending(val sequence: Long, val entry: LabLogEntry, val history: Long, val connection: Long?)
    private data class Request(val sequence: Long, val execute: () -> Unit)
    private sealed class Work {
        data class Batch(val entries: List<Pending>) : Work()
        data class Control(val request: Request) : Work()
    }

    init {
        scope.launch {
            for (signal in wake) {
                while (true) {
                    val work = synchronized(lock) { nextWork() } ?: break
                    when (work) {
                        is Work.Control -> work.request.execute()
                        is Work.Batch -> writeBatch(work.entries)
                    }
                    // A new wave of records gets another batching interval. User requests are barriers.
                    if (work is Work.Batch && synchronized(lock) { requests.isEmpty() }) break
                }
            }
        }
    }

    fun initialize(factory: () -> LogStorage, stagingDirectory: () -> File) {
        synchronized(lock) {
            if (initializationRequested) return
            initializationRequested = true
            requests.addLast(Request(++sequence) {
                try {
                    val directory = stagingDirectory()
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建日志导出目录" }
                    // Staging files are private and never backed up. Remove leftovers after process death.
                    directory.listFiles()?.filter { it.name.startsWith("rr-log-export-") }?.forEach { it.delete() }
                    snapshotDirectory = directory
                    val previous = storage.query(RRLogFilter(), null, 2000).entries.asReversed()
                    val opened = factory()
                    try { opened.append(previous.map { it.asLabEntry() }) } catch (error: Exception) {
                        opened.close(); throw error
                    }
                    storage.close()
                    storage = opened
                    publish(ready = true)
                } catch (error: Exception) {
                    failStorage(error)
                    publish(ready = true)
                }
            })
        }
        wake.trySend(Unit)
    }

    fun record(channel: String, message: String, timestamp: Long = System.currentTimeMillis()) {
        val cleaned = SecretRedactor.redact(message).take(4096).trim()
        if (cleaned.isBlank()) return
        val safeChannel = SecretRedactor.redact(channel).filterNot(Char::isISOControl).take(64).ifBlank { "LOG" }
        synchronized(lock) {
            if (safeChannel == ConnectionRouteLog.CHANNEL && !mutableConnectionActive.value) return
            enqueue(LabLogEntry(timestamp, safeChannel, cleaned))
            scheduleFlush()
        }
    }

    fun recordConnections(messages: List<ConnectionRouteMessage>) {
        synchronized(lock) {
            if (!mutableConnectionActive.value) return
            messages.forEach { message ->
                val cleaned = SecretRedactor.redact(message.message).take(4096).trim()
                if (cleaned.isNotEmpty()) enqueue(LabLogEntry(message.timestamp, ConnectionRouteLog.CHANNEL, cleaned))
            }
            if (pending.isNotEmpty()) scheduleFlush()
        }
    }

    fun setConnectionLoggingActive(active: Boolean) {
        synchronized(lock) {
            mutableConnectionActive.value = active
            // Accepted records belong to history even if the core stops before the next flush.
            // Only clearHistory invalidates accepted records; a disconnect rejects future events.
        }
    }

    private fun enqueue(entry: LabLogEntry) {
        if (pending.size >= pendingCapacity) {
            if (pending.removeFirst().connection == null) droppedOther++ else droppedRoutes++
            val overflow = overflowMessage(droppedOther + droppedRoutes)
            mutableState.value = mutableState.value.copy(error = if (diskUnavailable) {
                "日志磁盘存储不可用，仅保留最多 2000 条内存日志。$overflow"
            } else overflow)
        }
        pending.addLast(Pending(++sequence, entry, historyGeneration,
            if (entry.channel == ConnectionRouteLog.CHANNEL) connectionGeneration else null))
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive != true) flushJob = scope.launch {
            delay(flushDelayMillis)
            synchronized(lock) { flushJob = null }
            wake.trySend(Unit)
        }
    }

    /** Called only under lock. Control sequence numbers keep clear/queries behind earlier records. */
    private fun nextWork(): Work? {
        val request = requests.firstOrNull()
        val first = pending.firstOrNull()
        if (request != null && (first == null || request.sequence < first.sequence)) {
            return Work.Control(requests.removeFirst())
        }
        if (first == null) return null
        val batch = ArrayList<Pending>()
        while (pending.isNotEmpty() && (request == null || pending.first().sequence < request.sequence)) {
            batch.add(pending.removeFirst())
        }
        val dropped = droppedOther + droppedRoutes
        if (dropped > 0) {
            batch.add(Pending(++sequence, LabLogEntry(channel = "LOG", message = overflowMessage(dropped)), historyGeneration, null))
            droppedOther = 0; droppedRoutes = 0
        }
        return Work.Batch(batch)
    }

    private fun writeBatch(batch: List<Pending>) {
        val accepted = synchronized(lock) { batch.filter(::isCurrent) }
        if (accepted.isEmpty()) return
        try {
            val inserted = storage.append(accepted.map { it.entry })
            // A stop/clear may arrive during SQLite IO. Do not resurrect its uncommitted pending batch.
            val obsolete = synchronized(lock) { accepted.indices.filterNot { isCurrent(accepted[it]) } }
            if (obsolete.isNotEmpty()) storage.deleteIds(obsolete.map { inserted[it].id })
            publish(expectedHistory = accepted.first().history)
        } catch (error: Exception) {
            failStorage(error)
            val current = synchronized(lock) { accepted.filter(::isCurrent).map { it.entry } }
            storage.append(current)
            publish(expectedHistory = accepted.first().history)
        }
    }

    private fun isCurrent(entry: Pending): Boolean = entry.history == historyGeneration

    suspend fun query(filter: RRLogFilter, beforeId: Long?, limit: Int): RRLogPage {
        require(limit in 1..500) { "日志分页大小须为 1–500" }
        return request {
            try { storage.query(filter, beforeId, limit) } catch (error: Exception) {
                failStorage(error); publish(); storage.query(filter, beforeId, limit)
            }
        }
    }

    suspend fun setRetentionLimit(limit: Int) {
        validateLogRetention(limit)
        request {
            if (diskUnavailable) {
                storage.setRetentionLimit(limit)
                publish()
                throw IllegalStateException("磁盘日志存储仍不可用；设置仅对内存生效，未持久保存")
            }
            try { storage.setRetentionLimit(limit); publish() } catch (error: Exception) {
                failStorage(error)
                storage.setRetentionLimit(limit)
                publish()
                throw IllegalStateException("保留设置未能保存到磁盘；当前仅对内存日志生效", error)
            }
        }
    }

    fun clear(): CompletableDeferred<Unit> {
        val result = CompletableDeferred<Unit>()
        synchronized(lock) {
            historyGeneration++
            pending.clear()
            droppedOther = 0; droppedRoutes = 0
            recent = emptyList()
            mutableEntries.value = emptyList()
            requests.addLast(Request(++sequence) {
                if (diskUnavailable) {
                    storage.clear(); publish()
                    result.completeExceptionally(IllegalStateException("仅清空了内存；磁盘历史尚未清空，日志存储恢复后请重试"))
                    return@Request
                }
                try {
                    storage.clear()
                    synchronized(lock) {
                        if (droppedOther + droppedRoutes == 0L) mutableState.value = mutableState.value.copy(error = null)
                    }
                    publish(); result.complete(Unit)
                } catch (error: Exception) {
                    failStorage(error); storage.clear(); publish()
                    result.completeExceptionally(IllegalStateException("磁盘日志清空失败；内存已清空，请重试", error))
                }
            })
        }
        wake.trySend(Unit)
        return result
    }

    suspend fun clearHistory() { clear().await() }

    private data class ExportSnapshot(val file: File?, val rows: List<RRStoredLogEntry>?, val count: Long)

    suspend fun export(output: OutputStream, filter: RRLogFilter): Long {
        val snapshot = request(onAbandoned = { it.file?.delete() }) {
            if (storage is MemoryLogStorage) {
                val rows = mutableListOf<RRStoredLogEntry>()
                storage.visitChronological(filter) { rows.add(it) }
                ExportSnapshot(null, rows, rows.size.toLong())
            } else {
                val directory = snapshotDirectory ?: error("日志存储尚未初始化")
                val file = File.createTempFile("rr-log-export-", ".txt", directory)
                try {
                    val count = file.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writeHeader(writer)
                        val date = dateFormatter()
                        storage.visitChronological(filter) { entry ->
                            // A canceled picker/export must not continue scanning a large history.
                            ensureRequestActive()
                            writeEntry(writer, entry, date)
                        }
                    }
                    ExportSnapshot(file, null, count)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        }
        return try {
            withContext(Dispatchers.IO) {
                if (snapshot.file != null) {
                    snapshot.file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActiveForLogExport()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.flush()
                } else {
                    val writer = output.writer(Charsets.UTF_8).buffered()
                    writeHeader(writer)
                    val date = dateFormatter()
                    snapshot.rows.orEmpty().forEach {
                        kotlinx.coroutines.currentCoroutineContext().ensureActiveForLogExport()
                        writeEntry(writer, it, date)
                    }
                    writer.flush()
                }
                snapshot.count
            }
        } finally { snapshot.file?.delete() }
    }

    // Only accessed by the single writer while a request is executing.
    private var activeRequest: CompletableDeferred<*>? = null
    private fun ensureRequestActive() {
        if (activeRequest?.isCancelled == true) throw CancellationException("日志导出已取消")
    }

    private suspend fun <T> request(onAbandoned: (T) -> Unit = {}, block: () -> T): T = requestSlots.withPermit {
        val result = CompletableDeferred<T>()
        // Own a produced value until await actually hands it to its caller. This also covers
        // prompt cancellation after complete() succeeds, before the continuation resumes.
        data class Produced<T>(val value: T)
        val produced = AtomicReference<Produced<T>?>(null)
        synchronized(lock) {
            requests.addLast(Request(++sequence) {
                if (!result.isCancelled) {
                    activeRequest = result
                    try {
                        val value = block()
                        produced.set(Produced(value))
                        if (!result.complete(value)) produced.getAndSet(null)?.let { onAbandoned(it.value) }
                    } catch (error: Exception) { result.completeExceptionally(error) }
                    finally { activeRequest = null }
                }
            })
        }
        wake.trySend(Unit)
        try {
            result.await().also { produced.set(null) }
        } catch (error: CancellationException) {
            result.cancel()
            produced.getAndSet(null)?.let { onAbandoned(it.value) }
            throw error
        }
    }

    private fun failStorage(error: Exception) {
        diskUnavailable = true
        val previous = runCatching { storage.metadata() }.getOrNull()
        runCatching { storage.close() }
        val seed = synchronized(lock) { recent }
        storage = MemoryLogStorage(previous?.retentionLimit ?: state.value.retentionLimit,
            seed, previous?.maxId ?: 0)
        val reason = SecretRedactor.redact(error.message ?: error.javaClass.simpleName).take(240)
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(ready = true,
                error = "日志磁盘存储不可用，仅保留最多 2000 条内存日志；历史磁盘记录暂不可用。$reason")
        }
    }

    private fun publish(ready: Boolean = state.value.ready, expectedHistory: Long? = null) {
        val metadata = storage.metadata()
        val rows = storage.query(RRLogFilter(), null, recentCapacity).entries.asReversed()
        synchronized(lock) {
            if (expectedHistory != null && expectedHistory != historyGeneration) return
            recent = rows
            mutableEntries.value = rows.map { it.asLabEntry() }.sortedBy { it.timestamp }
            mutableState.value = mutableState.value.copy(ready = ready, retentionLimit = metadata.retentionLimit,
                totalCount = metadata.totalCount, revision = mutableState.value.revision + 1)
        }
    }

    internal fun closeForTests() { scope.cancel(); storage.close() }

    private fun overflowMessage(count: Long) =
        "日志写入队列已满，本批省略 $count 条较早记录；待写队列上限 $pendingCapacity 条，与历史保留设置无关。"

    companion object {
        private fun dateFormatter() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT)
        private fun writeHeader(writer: Writer) {
            writer.write("RRBOX 日志中心（已自动脱敏）\n日期时间\t时间戳(ms)\t通道\t内容\n")
        }
        private fun writeEntry(writer: Writer, entry: RRStoredLogEntry, date: SimpleDateFormat) {
            writer.write("${date.format(Date(entry.timestamp))}\t${entry.timestamp}\t${entry.channel}\t${SecretRedactor.redact(entry.message)}\n")
        }
    }
}

private fun kotlin.coroutines.CoroutineContext.ensureActiveForLogExport() {
    if (this[Job]?.isActive == false) throw CancellationException("日志导出已取消")
}

package com.rr.client.lab

import java.io.Closeable

internal data class LogStorageMetadata(val retentionLimit: Int, val totalCount: Long, val maxId: Long)

/** All methods run on the log writer, never on the VPN callback or main thread. */
internal interface LogStorage : Closeable {
    fun metadata(): LogStorageMetadata
    fun append(entries: List<LabLogEntry>): List<RRStoredLogEntry>
    fun deleteIds(ids: List<Long>)
    fun setRetentionLimit(limit: Int)
    fun clear()
    fun query(filter: RRLogFilter, beforeId: Long?, limit: Int): RRLogPage
    fun visitChronological(filter: RRLogFilter, visitor: (RRStoredLogEntry) -> Unit): Long
    override fun close() = Unit
}

/** Only used before initialization or after a disk error; unlimited never means unlimited RAM. */
internal class MemoryLogStorage(
    private var retentionLimit: Int = 2000,
    seed: List<RRStoredLogEntry> = emptyList(),
    initialMaxId: Long = 0,
    private val capacity: Int = 2000
) : LogStorage {
    private val rows = ArrayDeque<RRStoredLogEntry>()
    private var maxId = maxOf(initialMaxId, seed.maxOfOrNull { it.id } ?: 0)

    init { rows.addAll(seed.sortedBy { it.id }); prune() }

    override fun metadata() = LogStorageMetadata(retentionLimit, rows.size.toLong(), maxId)

    override fun append(entries: List<LabLogEntry>): List<RRStoredLogEntry> {
        val inserted = entries.map { RRStoredLogEntry(++maxId, it.timestamp, it.channel, it.message) }
        rows.addAll(inserted)
        prune()
        return inserted
    }

    override fun deleteIds(ids: List<Long>) {
        val removed = ids.toHashSet()
        rows.removeAll { it.id in removed }
    }

    override fun setRetentionLimit(limit: Int) {
        validateLogRetention(limit)
        retentionLimit = limit
        prune()
    }

    override fun clear() = rows.clear()

    private fun prune() {
        val keep = if (retentionLimit == 0) capacity else minOf(capacity, retentionLimit)
        while (rows.size > keep) rows.removeFirst()
    }

    override fun query(filter: RRLogFilter, beforeId: Long?, limit: Int): RRLogPage {
        val matching = rows.filter { matches(it, filter) }
        val page = matching.asReversed().asSequence()
            .filter { beforeId == null || it.id < beforeId }.take(limit + 1).toList()
        return RRLogPage(page.take(limit), matching.size.toLong(),
            if (page.size > limit) page[limit - 1].id else null)
    }

    override fun visitChronological(filter: RRLogFilter, visitor: (RRStoredLogEntry) -> Unit): Long {
        val matching = rows.filter { matches(it, filter) }.sortedWith(compareBy({ it.timestamp }, { it.id }))
        matching.forEach(visitor)
        return matching.size.toLong()
    }

    private fun matches(row: RRStoredLogEntry, filter: RRLogFilter): Boolean =
        (filter.channel.isNullOrBlank() || row.channel == filter.channel) &&
            (filter.search.isBlank() || row.message.contains(filter.search, ignoreCase = true) ||
                row.channel.contains(filter.search, ignoreCase = true))
}

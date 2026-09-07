package com.rr.client.lab

data class RRLogState(
    val ready: Boolean = false,
    val retentionLimit: Int = 2000,
    val totalCount: Long = 0,
    val revision: Long = 0,
    val error: String? = null
)

data class RRLogFilter(val channel: String? = null, val search: String = "")

data class RRStoredLogEntry(
    val id: Long,
    val timestamp: Long,
    val channel: String,
    val message: String
) {
    fun asLabEntry() = LabLogEntry(timestamp, channel, message)
}

/** Rows are newest ID first. The count covers the whole filter, not just this page. */
data class RRLogPage(
    val entries: List<RRStoredLogEntry>,
    val filteredCount: Long,
    val nextBeforeId: Long?
)

internal fun validateLogRetention(limit: Int) {
    require(limit == 0 || limit in 100..1_000_000) {
        "日志保留条数须为 100–1000000，或 0（不限）"
    }
}

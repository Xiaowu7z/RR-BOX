package com.rr.client.traffic

import java.util.Locale

/**
 * Runtime speed sampled from sing-box's cumulative traffic counters.
 * The formatted properties are derived from the byte counters so UI and
 * notifications cannot accidentally keep displaying a stale placeholder.
 */
data class TrafficSpeed(
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L
) {
    val formattedDownSpeed: String
        get() = formatRate(downloadBytesPerSec)

    val formattedUpSpeed: String
        get() = formatRate(uploadBytesPerSec)
}

data class SessionTraffic(
    val proxyDownloadTotal: Long = 0L,
    val proxyUploadTotal: Long = 0L,
    val directDownloadTotal: Long = 0L,
    val directUploadTotal: Long = 0L,
    val durationSeconds: Long = 0L
)

private fun formatRate(rawBytesPerSecond: Long): String {
    val value = rawBytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f GB/s", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format(Locale.US, "%.2f MB/s", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format(Locale.US, "%.1f KB/s", value / 1024.0)
        else -> "${value.toLong()} B/s"
    }
}

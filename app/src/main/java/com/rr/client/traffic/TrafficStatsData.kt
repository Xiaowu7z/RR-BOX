package com.rr.client.traffic

import java.util.Locale

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
    val bytesPerSecond = rawBytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        bytesPerSecond >= 1024.0 * 1024.0 * 1024.0 -> String.format(
            Locale.US,
            "%.2f GB/s",
            bytesPerSecond / (1024.0 * 1024.0 * 1024.0)
        )

        bytesPerSecond >= 1024.0 * 1024.0 -> String.format(
            Locale.US,
            "%.2f MB/s",
            bytesPerSecond / (1024.0 * 1024.0)
        )

        bytesPerSecond >= 1024.0 -> String.format(
            Locale.US,
            "%.1f KB/s",
            bytesPerSecond / 1024.0
        )

        else -> "${bytesPerSecond.toLong()} B/s"
    }
}

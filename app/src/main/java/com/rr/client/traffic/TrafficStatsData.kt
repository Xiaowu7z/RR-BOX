package com.rr.client.traffic

data class TrafficSpeed(
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val formattedDownSpeed: String = "0 B/s",
    val formattedUpSpeed: String = "0 B/s"
)

data class SessionTraffic(
    val proxyDownloadTotal: Long = 0L,
    val proxyUploadTotal: Long = 0L,
    val directDownloadTotal: Long = 0L,
    val directUploadTotal: Long = 0L,
    val durationSeconds: Long = 0L
)

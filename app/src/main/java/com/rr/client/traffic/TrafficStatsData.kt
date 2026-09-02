package com.rr.client.traffic

data class TrafficSpeed(
    val uploadBytesPerSec: Long = 0L,
    val downloadBytesPerSec: Long = 0L,
    val formattedDownSpeed: String = TrafficSampler.formatSpeed(downloadBytesPerSec),
    val formattedUpSpeed: String = TrafficSampler.formatSpeed(uploadBytesPerSec)
)

data class SessionTraffic(
    val proxyDownloadTotal: Long = 0L,
    val proxyUploadTotal: Long = 0L,
    val directDownloadTotal: Long = 0L,
    val directUploadTotal: Long = 0L,
    val durationSeconds: Long = 0L
)

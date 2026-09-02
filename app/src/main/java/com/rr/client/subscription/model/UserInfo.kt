package com.rr.client.subscription.model

data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireTimestamp: Long = 0L
) {
    val usedBytes: Long get() = upload + download
    val remainingBytes: Long get() = if (total > usedBytes) total - usedBytes else 0L
    val usagePercentage: Float get() = if (total > 0L) (usedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

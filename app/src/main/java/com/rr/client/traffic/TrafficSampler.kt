package com.rr.client.traffic

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * TrafficSampler guarantees:
 * 1. Monotonic clock (SystemClock.elapsedRealtime()) for rate calculations.
 * 2. Strict Outbound Proxy sampling, explicitly excluding Direct and Bypass traffic.
 * 3. Anomaly and reset protection (no negative deltas, no multi-GB/s single-frame spikes).
 * 4. Periodic batch DB flush (every 5-10 seconds) without locking the main thread.
 */
class TrafficSampler(
    private val scope: CoroutineScope,
    private val queryProxyStats: () -> Pair<Long, Long>, // returns Pair(cumulativeDown, cumulativeUp) for "proxy" outbound
    private val onBatchFlush: (SessionTraffic) -> Unit
) {
    private val _currentSpeed = MutableStateFlow(TrafficSpeed())
    val currentSpeed: StateFlow<TrafficSpeed> = _currentSpeed.asStateFlow()

    private val _sessionTraffic = MutableStateFlow(SessionTraffic())
    val sessionTraffic: StateFlow<SessionTraffic> = _sessionTraffic.asStateFlow()

    private var sampleJob: Job? = null
    private var lastSampleTimeMs: Long = 0L
    private var lastProxyDown: Long = -1L
    private var lastProxyUp: Long = -1L
    private var sessionStartTimeMs: Long = 0L
    private var lastFlushTimeMs: Long = 0L

    fun start() {
        reset()
        lastSampleTimeMs = SystemClock.elapsedRealtime()
        sessionStartTimeMs = lastSampleTimeMs
        lastFlushTimeMs = lastSampleTimeMs

        sampleJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sample()
                delay(1000L) // Fixed 1-second cadence
            }
        }
    }

    fun stop() {
        sampleJob?.cancel()
        sampleJob = null
        onBatchFlush(_sessionTraffic.value)
    }

    fun reset() {
        lastProxyDown = -1L
        lastProxyUp = -1L
        lastSampleTimeMs = 0L
        _currentSpeed.value = TrafficSpeed()
        _sessionTraffic.value = SessionTraffic()
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val deltaMs = max(1L, now - lastSampleTimeMs)
        val (cumDown, cumUp) = queryProxyStats()

        if (lastProxyDown < 0L || lastProxyUp < 0L) {
            // First baseline sample
            lastProxyDown = cumDown
            lastProxyUp = cumUp
            lastSampleTimeMs = now
            return
        }

        // Compute deltas with counter-reset protection
        val deltaDown = if (cumDown >= lastProxyDown) cumDown - lastProxyDown else 0L
        val deltaUp = if (cumUp >= lastProxyUp) cumUp - lastProxyUp else 0L

        // Protection against impossible single-frame anomalies (> 2GB/s)
        val safeDeltaDown = if (deltaDown > 2_000_000_000L) 0L else deltaDown
        val safeDeltaUp = if (deltaUp > 2_000_000_000L) 0L else deltaUp

        val downSpeed = (safeDeltaDown * 1000L) / deltaMs
        val upSpeed = (safeDeltaUp * 1000L) / deltaMs

        lastProxyDown = cumDown
        lastProxyUp = cumUp
        lastSampleTimeMs = now

        _currentSpeed.value = TrafficSpeed(
            uploadBytesPerSec = upSpeed,
            downloadBytesPerSec = downSpeed,
            formattedDownSpeed = formatSpeed(downSpeed),
            formattedUpSpeed = formatSpeed(upSpeed)
        )

        val currentSession = _sessionTraffic.value
        val updatedSession = currentSession.copy(
            proxyDownloadTotal = currentSession.proxyDownloadTotal + safeDeltaDown,
            proxyUploadTotal = currentSession.proxyUploadTotal + safeDeltaUp,
            durationSeconds = (now - sessionStartTimeMs) / 1000L
        )
        _sessionTraffic.value = updatedSession

        // Periodic batch persistence every 5 seconds
        if (now - lastFlushTimeMs >= 5000L) {
            lastFlushTimeMs = now
            onBatchFlush(updatedSession)
        }
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            return when {
                bytesPerSec >= 1024 * 1024 * 1024 -> String.format("%.2f GB/s", bytesPerSec / (1024.0 * 1024.0 * 1024.0))
                bytesPerSec >= 1024 * 1024 -> String.format("%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
                bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
                else -> "$bytesPerSec B/s"
            }
        }

        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
                bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}

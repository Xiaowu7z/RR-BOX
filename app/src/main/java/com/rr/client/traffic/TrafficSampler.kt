package com.rr.client.traffic

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Samples cumulative byte counters with a monotonic clock.
 *
 * The production VPN service currently receives counters directly from
 * libbox's CommandStatus stream. This class remains available for components
 * that need a polling adapter, but it must use the same TrafficSpeed model.
 */
class TrafficSampler(
    private val scope: CoroutineScope,
    private val queryProxyStats: () -> Pair<Long, Long>,
    private val onBatchFlush: (SessionTraffic) -> Unit
) {
    private val _currentSpeed = MutableStateFlow(TrafficSpeed())
    val currentSpeed: StateFlow<TrafficSpeed> = _currentSpeed.asStateFlow()

    private val _sessionTraffic = MutableStateFlow(SessionTraffic())
    val sessionTraffic: StateFlow<SessionTraffic> = _sessionTraffic.asStateFlow()

    private var sampleJob: Job? = null
    private var lastSampleTimeMs = 0L
    private var lastProxyDown = -1L
    private var lastProxyUp = -1L
    private var sessionStartTimeMs = 0L
    private var lastFlushTimeMs = 0L

    fun start() {
        stop(flush = false)
        reset()
        lastSampleTimeMs = SystemClock.elapsedRealtime()
        sessionStartTimeMs = lastSampleTimeMs
        lastFlushTimeMs = lastSampleTimeMs

        sampleJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sample()
                delay(1000L)
            }
        }
    }

    fun stop() = stop(flush = true)

    private fun stop(flush: Boolean) {
        sampleJob?.cancel()
        sampleJob = null
        if (flush) onBatchFlush(_sessionTraffic.value)
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
            lastProxyDown = cumDown.coerceAtLeast(0L)
            lastProxyUp = cumUp.coerceAtLeast(0L)
            lastSampleTimeMs = now
            return
        }

        val deltaDown = if (cumDown >= lastProxyDown) cumDown - lastProxyDown else 0L
        val deltaUp = if (cumUp >= lastProxyUp) cumUp - lastProxyUp else 0L

        val safeDeltaDown = if (deltaDown > 2_000_000_000L) 0L else deltaDown
        val safeDeltaUp = if (deltaUp > 2_000_000_000L) 0L else deltaUp

        val downSpeed = safeRate(safeDeltaDown, deltaMs)
        val upSpeed = safeRate(safeDeltaUp, deltaMs)

        lastProxyDown = cumDown.coerceAtLeast(0L)
        lastProxyUp = cumUp.coerceAtLeast(0L)
        lastSampleTimeMs = now

        _currentSpeed.value = TrafficSpeed(
            uploadBytesPerSec = upSpeed,
            downloadBytesPerSec = downSpeed
        )

        val updatedSession = _sessionTraffic.value.copy(
            proxyDownloadTotal = _sessionTraffic.value.proxyDownloadTotal + safeDeltaDown,
            proxyUploadTotal = _sessionTraffic.value.proxyUploadTotal + safeDeltaUp,
            durationSeconds = (now - sessionStartTimeMs).coerceAtLeast(0L) / 1000L
        )
        _sessionTraffic.value = updatedSession

        if (now - lastFlushTimeMs >= 5000L) {
            lastFlushTimeMs = now
            onBatchFlush(updatedSession)
        }
    }

    private fun safeRate(bytes: Long, elapsedMs: Long): Long {
        if (bytes <= 0L || elapsedMs <= 0L) return 0L
        return runCatching { Math.multiplyExact(bytes, 1000L) / elapsedMs }
            .getOrElse {
                (bytes.toDouble() * 1000.0 / elapsedMs.toDouble())
                    .coerceAtMost(Long.MAX_VALUE.toDouble())
                    .toLong()
            }
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String = TrafficSpeed(
            downloadBytesPerSec = bytesPerSec.coerceAtLeast(0L)
        ).formattedDownSpeed

        fun formatBytes(bytes: Long): String {
            val safeBytes = bytes.coerceAtLeast(0L)
            return when {
                safeBytes >= 1024L * 1024L * 1024L -> String.format(
                    "%.2f GB",
                    safeBytes / (1024.0 * 1024.0 * 1024.0)
                )
                safeBytes >= 1024L * 1024L -> String.format(
                    "%.2f MB",
                    safeBytes / (1024.0 * 1024.0)
                )
                safeBytes >= 1024L -> String.format("%.1f KB", safeBytes / 1024.0)
                else -> "$safeBytes B"
            }
        }
    }
}

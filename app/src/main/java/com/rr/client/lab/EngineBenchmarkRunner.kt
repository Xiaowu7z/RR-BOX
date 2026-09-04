package com.rr.client.lab

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.NodeLatencyTester
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.PreferencesManager
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

class EngineBenchmarkRunner(
    context: Context,
    private val preferences: PreferencesManager,
    private val node: ProxyNode,
    private val observationSeconds: Int = 5
) {
    private val appContext = context.applicationContext

    suspend fun run(): EngineBenchmarkReport {
        require(RRVpnService.isRunning.value && !RRVpnService.isStarting.value) {
            "请先让 RRBOX 正常连接，再开始 System / HEV A/B"
        }
        val originalEngine = preferences.tunEngine.first()
        RRLogStore.record("BENCH", "开始 A/B: node=${node.tag}, original=$originalEngine")

        return try {
            val system = sampleEngine(PreferencesManager.TUN_ENGINE_SYSTEM)
            val hev = sampleEngine(PreferencesManager.TUN_ENGINE_HEV)
            EngineBenchmarkReport(
                nodeTag = node.tag,
                nodeServerMasked = maskHost(node.server),
                originalEngine = originalEngine,
                system = system,
                hev = hev
            ).also {
                BenchmarkHistoryStore.save(appContext, it)
                RRLogStore.record("BENCH", "A/B 完成: System=${system.restartMillis}ms HEV=${hev.restartMillis}ms")
            }
        } finally {
            restoreEngine(originalEngine)
        }
    }

    private suspend fun sampleEngine(engine: String): EngineBenchmarkSample {
        val restartMillis = restartInto(engine)
        delay(500L)

        val ping = when (val state = NodeLatencyTester.ping(node.server)) {
            is NodeLatencyState.Success -> state.millis
            else -> null
        }

        val startTraffic = RRVpnService.sessionTraffic.value
        val cpuStart = Process.getElapsedCpuTime()
        var peakDown = 0L
        var peakUp = 0L
        var sumDown = 0L
        var sumUp = 0L
        var samples = 0

        repeat(observationSeconds.coerceIn(3, 20) * 2) {
            val speed = RRVpnService.currentSpeed.value
            peakDown = max(peakDown, speed.downloadBytesPerSec)
            peakUp = max(peakUp, speed.uploadBytesPerSec)
            sumDown += speed.downloadBytesPerSec.coerceAtLeast(0L)
            sumUp += speed.uploadBytesPerSec.coerceAtLeast(0L)
            samples += 1
            delay(500L)
        }

        val endTraffic = RRVpnService.sessionTraffic.value
        val cpuDelta = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0L)
        val sample = EngineBenchmarkSample(
            engine = engine,
            restartMillis = restartMillis,
            pingMillis = ping,
            observedAverageDownloadBps = if (samples > 0) sumDown / samples else 0L,
            observedAverageUploadBps = if (samples > 0) sumUp / samples else 0L,
            observedPeakDownloadBps = peakDown,
            observedPeakUploadBps = peakUp,
            trafficDownloadDelta = (endTraffic.proxyDownloadTotal - startTraffic.proxyDownloadTotal).coerceAtLeast(0L),
            trafficUploadDelta = (endTraffic.proxyUploadTotal - startTraffic.proxyUploadTotal).coerceAtLeast(0L),
            processCpuMillis = cpuDelta,
            processPssKb = Debug.getPss()
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            observationSeconds = observationSeconds.coerceIn(3, 20)
        )
        RRLogStore.record(
            "BENCH",
            "$engine sample: restart=${sample.restartMillis}ms ping=${sample.pingMillis ?: -1}ms " +
                "peakDown=${sample.observedPeakDownloadBps} peakUp=${sample.observedPeakUploadBps}"
        )
        return sample
    }

    private suspend fun restartInto(engine: String): Long {
        preferences.setTunEngine(engine)
        val startedAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, RRVpnService::class.java).apply {
                action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
            }
        )

        var sawStarting = RRVpnService.isStarting.value
        val ok = withTimeoutOrNull(20_000L) {
            while (!(sawStarting && !RRVpnService.isStarting.value && RRVpnService.isRunning.value)) {
                if (RRVpnService.isStarting.value) sawStarting = true
                if (sawStarting && !RRVpnService.isStarting.value && !RRVpnService.isRunning.value) {
                    error(RRVpnService.lastError.value ?: "$engine 引擎重建失败")
                }
                delay(80L)
            }
            true
        } ?: false

        check(ok) { "$engine 引擎切换超时" }
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private suspend fun restoreEngine(originalEngine: String) {
        val currentPreference = runCatching { preferences.tunEngine.first() }.getOrDefault(originalEngine)
        if (currentPreference == originalEngine) return
        preferences.setTunEngine(originalEngine)
        if (!RRVpnService.isRunning.value || RRVpnService.isStarting.value) return
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, RRVpnService::class.java).apply {
                    action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
                }
            )
        }
        RRLogStore.record("BENCH", "已恢复原始引擎偏好: $originalEngine")
    }

    private fun maskHost(value: String): String {
        val host = value.trim()
        val ipv4 = host.split('.')
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
            return "${ipv4.first()}.***.***.${ipv4.last()}"
        }
        if (host.contains(':')) return "[${host.substringBefore(':')}:****]"
        if (host.length <= 4) return "***"
        return "${host.take(2)}***${host.takeLast(2)}"
    }
}

object BenchmarkHistoryStore {
    private const val PREFS = "rrbox_network_lab"
    private const val KEY = "benchmark_history"
    private const val MAX_REPORTS = 20
    private val gson = Gson()
    private val type = object : TypeToken<List<EngineBenchmarkReport>>() {}.type

    fun load(context: Context): List<EngineBenchmarkReport> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching { gson.fromJson<List<EngineBenchmarkReport>>(raw, type).orEmpty() }
            .getOrDefault(emptyList())
    }

    fun save(context: Context, report: EngineBenchmarkReport) {
        val next = (listOf(report) + load(context)).take(MAX_REPORTS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, gson.toJson(next))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

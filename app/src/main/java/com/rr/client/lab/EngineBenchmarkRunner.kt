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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class EngineBenchmarkRunner(
    context: Context,
    private val preferences: PreferencesManager,
    private val node: ProxyNode,
    private val onProgress: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext

    suspend fun run(): EngineBenchmarkReport {
        require(RRVpnService.isRunning.value && !RRVpnService.isStarting.value) {
            "请先让 RRBOX 正常连接，再开始 System / HEV A/B"
        }
        val originalEngine = preferences.tunEngine.first()
        RRLogStore.record("BENCH", "开始 A/B v2: node=${node.tag}, original=$originalEngine")

        return try {
            val system = sampleEngine(PreferencesManager.TUN_ENGINE_SYSTEM)
            val hev = sampleEngine(PreferencesManager.TUN_ENGINE_HEV)
            EngineBenchmarkReport(
                benchmarkVersion = 2,
                nodeTag = node.tag,
                nodeServerMasked = maskHost(node.server),
                originalEngine = originalEngine,
                probeTarget = EngineBenchmarkProbe.HTTPS_HOST,
                udpTarget = "${EngineBenchmarkProbe.UDP_HOST}:${EngineBenchmarkProbe.UDP_PORT}",
                system = system,
                hev = hev
            ).also {
                BenchmarkHistoryStore.save(appContext, it)
                RRLogStore.record(
                    "BENCH",
                    "A/B v2 完成: System TTFB=${system.httpsFirstByteMedianMillis ?: -1}ms " +
                        "HEV TTFB=${hev.httpsFirstByteMedianMillis ?: -1}ms"
                )
            }
        } finally {
            withContext(NonCancellable) {
                onProgress("正在恢复原始引擎")
                runCatching { restoreEngine(originalEngine) }
                    .onFailure { RRLogStore.record("BENCH", "恢复原始引擎失败: ${it.message.orEmpty()}") }
                onProgress("A/B v2 已结束")
            }
        }
    }

    private suspend fun sampleEngine(engine: String): EngineBenchmarkSample {
        onProgress("$engine · 正在重建引擎")
        val restartMillis = restartInto(engine)
        delay(800L)

        onProgress("$engine · 原始 ICMP 参考")
        val rawIcmp = when (val state = NodeLatencyTester.ping(node.server)) {
            is NodeLatencyState.Success -> state.millis
            else -> null
        }

        val cpuStart = Process.getElapsedCpuTime()
        val httpsRounds = buildList {
            repeat(EngineBenchmarkProbe.HTTPS_ATTEMPTS) { index ->
                val attempt = index + 1
                onProgress("$engine · HTTPS $attempt/${EngineBenchmarkProbe.HTTPS_ATTEMPTS} · 2 MiB")
                val result = EngineBenchmarkProbe.httpsRound(attempt)
                add(result)
                RRLogStore.record(
                    "BENCH",
                    "$engine HTTPS#$attempt success=${result.success} " +
                        "ttfb=${result.firstByteMillis ?: -1}ms " +
                        "rate=${result.downloadBps ?: -1} verified=${result.proxyPathVerified}"
                )
                delay(300L)
            }
        }

        val udpRounds = buildList {
            repeat(EngineBenchmarkProbe.UDP_ATTEMPTS) { index ->
                val attempt = index + 1
                onProgress("$engine · UDP STUN $attempt/${EngineBenchmarkProbe.UDP_ATTEMPTS}")
                val result = EngineBenchmarkProbe.udpRound(attempt)
                add(result)
                RRLogStore.record(
                    "BENCH",
                    "$engine UDP#$attempt success=${result.success} rtt=${result.rttMillis ?: -1}ms"
                )
                delay(200L)
            }
        }

        val cpuDelta = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0L)
        val sample = EngineBenchmarkSample(
            engine = engine,
            restartMillis = restartMillis,
            rawIcmpMillis = rawIcmp,
            httpsRounds = httpsRounds,
            udpRounds = udpRounds,
            processCpuMillis = cpuDelta,
            processPssKb = Debug.getPss()
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            downloadBytesPerRound = EngineBenchmarkProbe.DOWNLOAD_BYTES
        )

        RRLogStore.record(
            "BENCH",
            "$engine v2 sample: restart=${sample.restartMillis}ms " +
                "https=${sample.httpsSuccessCount}/${sample.httpsAttemptCount} " +
                "ttfb=${sample.httpsFirstByteMedianMillis ?: -1}ms " +
                "udp=${sample.udpSuccessCount}/${sample.udpAttemptCount} " +
                "verified=${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}"
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

        if (!RRVpnService.isRunning.value || RRVpnService.isStarting.value) {
            preferences.setTunEngine(originalEngine)
            RRLogStore.record("BENCH", "VPN 非稳定运行态，仅恢复引擎偏好: $originalEngine")
            return
        }

        val restoreMillis = restartInto(originalEngine)
        RRLogStore.record("BENCH", "已恢复原始引擎: $originalEngine (${restoreMillis}ms)")
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

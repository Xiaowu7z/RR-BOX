package com.rr.client.lab

import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

        return try {
            onProgress("正在固定测速 IPv4 目标")
            val target = EngineBenchmarkProbe.resolveTarget(appContext)
            RRLogStore.record(
                "BENCH",
                "开始 A/B v2.5: node=${node.tag}, original=$originalEngine, " +
                    "transport=${EngineBenchmarkProbe.PROBE_TRANSPORT}, target=${target.label}"
            )

            val system = sampleEngine(
                engine = PreferencesManager.TUN_ENGINE_SYSTEM,
                target = target,
                includeSelfForHevBenchmark = false
            )
            val hev = sampleEngine(
                engine = PreferencesManager.TUN_ENGINE_HEV,
                target = target,
                includeSelfForHevBenchmark = true
            )
            EngineBenchmarkReport(
                benchmarkVersion = 7,
                nodeTag = node.tag,
                nodeServerMasked = maskHost(node.server),
                originalEngine = originalEngine,
                probeTarget = EngineBenchmarkProbe.HTTPS_HOST,
                helperPackage = "${EngineBenchmarkProbe.PROBE_TRANSPORT} · ${target.label}",
                udpTarget = "disabled-v2.5",
                system = system,
                hev = hev
            ).also {
                BenchmarkHistoryStore.save(appContext, it)
                RRLogStore.record(
                    "BENCH",
                    "A/B v2.5 完成: target=${target.addressText} " +
                        "System TTFB=${system.httpsFirstByteMedianMillis ?: -1}ms " +
                        "HEV TTFB=${hev.httpsFirstByteMedianMillis ?: -1}ms"
                )
            }
        } finally {
            withContext(NonCancellable) {
                onProgress("正在恢复原始引擎")
                runCatching { restoreEngine(originalEngine) }
                    .onFailure {
                        RRLogStore.record("BENCH", "恢复原始引擎失败: ${it.message.orEmpty()}")
                    }
                onProgress("A/B v2.5 已结束")
            }
        }
    }

    private suspend fun sampleEngine(
        engine: String,
        target: EngineBenchmarkProbe.ProbeTarget,
        includeSelfForHevBenchmark: Boolean
    ): EngineBenchmarkSample {
        onProgress(
            if (includeSelfForHevBenchmark) {
                "$engine · 正在重建 A/B v2.5 临时测试路由"
            } else {
                "$engine · 正在按正常模式重建引擎"
            }
        )
        val restartMillis = restartInto(engine, includeSelfForHevBenchmark)
        delay(900L)

        onProgress("$engine · 64 KiB UID→TUN 路径预检")
        val preflight = EngineBenchmarkProbe.httpsRound(
            context = appContext,
            engine = engine,
            target = target,
            attempt = 0,
            downloadBytes = EngineBenchmarkProbe.PREFLIGHT_BYTES
        )
        RRLogStore.record(
            "BENCH",
            "$engine PREFLIGHT success=${preflight.success} protocol=${preflight.protocol.orEmpty()} " +
                "bytes=${preflight.bytesReceived} proxyCount=${preflight.proxyAccountedDownloadBytes} " +
                "nativeRx=${preflight.nativeAccountedDownloadBytes} " +
                "nativeVerified=${preflight.nativePathVerified} verified=${preflight.proxyPathVerified}"
        )
        check(preflight.success) {
            "$engine UID→TUN 预检失败：${preflight.error ?: "HTTPS 未成功"}"
        }
        check(preflight.proxyPathVerified) {
            buildString {
                append("$engine UID→TUN 流量未通过代理路径校验：")
                append("下载 ${preflight.bytesReceived} B，sing-box 计入 ")
                append("${preflight.proxyAccountedDownloadBytes} B")
                if (engine == PreferencesManager.TUN_ENGINE_HEV) {
                    append("，HEV native RX ${preflight.nativeAccountedDownloadBytes} B")
                }
                append("；已停止测试，避免生成旁路成绩")
            }
        }
        delay(250L)

        val cpuStart = Process.getElapsedCpuTime()
        val httpsRounds = buildList {
            repeat(EngineBenchmarkProbe.HTTPS_ATTEMPTS) { index ->
                val attempt = index + 1
                onProgress("$engine · 固定 IPv4 HTTPS $attempt/${EngineBenchmarkProbe.HTTPS_ATTEMPTS} · 2 MiB")
                val result = EngineBenchmarkProbe.httpsRound(
                    context = appContext,
                    engine = engine,
                    target = target,
                    attempt = attempt
                )
                add(result)
                RRLogStore.record(
                    "BENCH",
                    "$engine HTTPS#$attempt success=${result.success} protocol=${result.protocol.orEmpty()} " +
                        "tcp=${result.tcpConnectMillis ?: -1} tls=${result.tlsMillis ?: -1} " +
                        "ttfb=${result.firstByteMillis ?: -1} rate=${result.downloadBps ?: -1} " +
                        "proxy=${result.proxyAccountedDownloadBytes} " +
                        "nativeRx=${result.nativeAccountedDownloadBytes} verified=${result.proxyPathVerified}"
                )
                if (result.success && !result.proxyPathVerified) {
                    error(
                        "$engine HTTPS#$attempt 已下载但路径计数验证失败；" +
                            "已停止测试，避免把旁路流量算作引擎成绩"
                    )
                }
                delay(300L)
            }
        }

        val verifiedHttps = httpsRounds.count { it.success && it.proxyPathVerified }
        check(verifiedHttps >= 2) {
            "$engine 有效 HTTPS 轮次不足：$verifiedHttps/${EngineBenchmarkProbe.HTTPS_ATTEMPTS}"
        }

        val cpuDelta = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0L)
        val sample = EngineBenchmarkSample(
            engine = engine,
            restartMillis = restartMillis,
            rawIcmpMillis = null,
            httpsRounds = httpsRounds,
            udpRounds = emptyList(),
            processCpuMillis = cpuDelta,
            processPssKb = Debug.getPss()
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            downloadBytesPerRound = EngineBenchmarkProbe.DOWNLOAD_BYTES
        )

        RRLogStore.record(
            "BENCH",
            "$engine v2.5 sample: restart=${sample.restartMillis}ms " +
                "https=${sample.httpsSuccessCount}/${sample.httpsAttemptCount} " +
                "ttfb=${sample.httpsFirstByteMedianMillis ?: -1}ms " +
                "verified=${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount} " +
                "nativeVerified=${sample.nativePathVerifiedCount}"
        )
        return sample
    }

    private suspend fun restartInto(
        engine: String,
        includeSelfForHevBenchmark: Boolean = false
    ): Long {
        preferences.setTunEngine(engine)
        RRVpnService.clearLastError()
        val startedAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, RRVpnService::class.java).apply {
                action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
                putExtra(
                    RRVpnService.EXTRA_HEV_BENCHMARK_SELF_TRAFFIC,
                    engine == PreferencesManager.TUN_ENGINE_HEV && includeSelfForHevBenchmark
                )
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

        check(ok) { RRVpnService.lastError.value ?: "$engine 引擎切换超时" }
        return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
    }

    private suspend fun restoreEngine(originalEngine: String) {
        // v2.5 HEV sample temporarily changes the per-UID VPN policy, so always perform one normal
        // restart after the experiment—even when the original engine was already HEV.
        preferences.setTunEngine(originalEngine)
        RRVpnService.clearLastError()

        val startedAt = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, RRVpnService::class.java).apply {
                action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
                putExtra(RRVpnService.EXTRA_HEV_BENCHMARK_SELF_TRAFFIC, false)
            }
        )

        var sawStarting = RRVpnService.isStarting.value
        val restored = withTimeoutOrNull(20_000L) {
            while (!(sawStarting && !RRVpnService.isStarting.value && RRVpnService.isRunning.value)) {
                if (RRVpnService.isStarting.value) sawStarting = true
                if (sawStarting && !RRVpnService.isStarting.value && !RRVpnService.isRunning.value) {
                    error(RRVpnService.lastError.value ?: "恢复 $originalEngine 失败")
                }
                delay(80L)
            }
            true
        } ?: false

        check(restored) { RRVpnService.lastError.value ?: "恢复 $originalEngine 超时" }
        RRLogStore.record(
            "BENCH",
            "已按正常模式恢复原始引擎: $originalEngine " +
                "(${(SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)}ms)"
        )
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

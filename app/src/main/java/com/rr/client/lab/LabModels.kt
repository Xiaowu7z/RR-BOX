package com.rr.client.lab

import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class LabCheckStatus {
    PASS,
    WARN,
    FAIL,
    INFO
}

data class LabCheck(
    val name: String,
    val status: LabCheckStatus,
    val detail: String
)

data class NetworkSnapshot(
    val transport: String = "未知",
    val activeInterface: String = "--",
    val mtu: Int = 0,
    val ipv4Addresses: List<String> = emptyList(),
    val ipv6Addresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val privateDns: String? = null,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val vpnInterface: String? = null,
    val vpnMtu: Int? = null
)

data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val snapshot: NetworkSnapshot = NetworkSnapshot(),
    val checks: List<LabCheck> = emptyList()
)

data class SelfCheckReport(
    val timestamp: Long = System.currentTimeMillis(),
    val checks: List<LabCheck> = emptyList()
) {
    val healthy: Boolean
        get() = checks.none { it.status == LabCheckStatus.FAIL }
}

data class HttpsProbeRound(
    val attempt: Int,
    val success: Boolean,
    val dnsMillis: Long? = null,
    val tcpConnectMillis: Long? = null,
    val tlsMillis: Long? = null,
    val firstByteMillis: Long? = null,
    val downloadMillis: Long? = null,
    val bytesReceived: Long = 0L,
    val downloadBps: Long? = null,
    val proxyAccountedDownloadBytes: Long = 0L,
    val nativeAccountedDownloadBytes: Long = 0L,
    val nativePathVerified: Boolean = false,
    val proxyPathVerified: Boolean = false,
    val httpCode: Int? = null,
    val protocol: String? = null,
    val error: String? = null
)

data class UdpProbeRound(
    val attempt: Int,
    val success: Boolean,
    val rttMillis: Long? = null,
    val address: String? = null,
    val error: String? = null
)

data class EngineBenchmarkSample(
    val engine: String,
    val restartMillis: Long,
    val rawIcmpMillis: Long? = null,
    val httpsRounds: List<HttpsProbeRound>? = emptyList(),
    val udpRounds: List<UdpProbeRound>? = emptyList(),
    val processCpuMillis: Long,
    val processPssKb: Int,
    val downloadBytesPerRound: Long = 0L
) {
    private val validHttpsRounds: List<HttpsProbeRound>
        get() = httpsRounds.orEmpty().filter { it.success && it.proxyPathVerified }

    val httpsAttemptCount: Int
        get() = httpsRounds.orEmpty().size

    val httpsSuccessCount: Int
        get() = httpsRounds.orEmpty().count { it.success }

    val proxyPathVerifiedCount: Int
        get() = validHttpsRounds.size

    val nativePathVerifiedCount: Int
        get() = httpsRounds.orEmpty().count { it.success && it.nativePathVerified }

    val httpsDnsMedianMillis: Long?
        get() = medianLong(validHttpsRounds.mapNotNull { it.dnsMillis })

    val httpsTcpMedianMillis: Long?
        get() = medianLong(validHttpsRounds.mapNotNull { it.tcpConnectMillis })

    val httpsTlsMedianMillis: Long?
        get() = medianLong(validHttpsRounds.mapNotNull { it.tlsMillis })

    val httpsFirstByteMedianMillis: Long?
        get() = medianLong(validHttpsRounds.mapNotNull { it.firstByteMillis })

    val httpsDownloadMedianBps: Long?
        get() = medianLong(validHttpsRounds.mapNotNull { it.downloadBps })

    val udpAttemptCount: Int
        get() = udpRounds.orEmpty().size

    val udpSuccessCount: Int
        get() = udpRounds.orEmpty().count { it.success }

    val udpMedianRttMillis: Long?
        get() = medianLong(udpRounds.orEmpty().mapNotNull { if (it.success) it.rttMillis else null })
}

data class EngineBenchmarkReport(
    val benchmarkVersion: Int = 5,
    val timestamp: Long = System.currentTimeMillis(),
    val nodeTag: String,
    val nodeServerMasked: String,
    val originalEngine: String,
    val probeTarget: String = "speed.cloudflare.com",
    val helperPackage: String = "",
    val udpTarget: String = "",
    val system: EngineBenchmarkSample,
    val hev: EngineBenchmarkSample
)

data class LabLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val channel: String,
    val message: String
)

data class MetricStats(
    val count: Int,
    val average: Double,
    val median: Double,
    val p95: Double,
    val stdDev: Double
)

data class EngineHistoryStats(
    val runs: Int,
    val restart: MetricStats?,
    val httpsFirstByte: MetricStats?,
    val downloadBps: MetricStats?,
    val pssKb: MetricStats?,
    val udpSuccesses: Int,
    val udpAttempts: Int,
    val proxyVerifiedRounds: Int,
    val nativeVerifiedRounds: Int,
    val httpsSuccessRounds: Int
)

data class BenchmarkHistoryStats(
    val runs: Int,
    val system: EngineHistoryStats,
    val hev: EngineHistoryStats
)

fun calculateMetricStats(values: List<Long>): MetricStats? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val average = sorted.average()
    val median = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2].toDouble()
    } else {
        (sorted[sorted.size / 2 - 1].toDouble() + sorted[sorted.size / 2].toDouble()) / 2.0
    }
    val p95Index = (ceil(sorted.size * 0.95).toInt().coerceIn(1, sorted.size) - 1)
    val variance = sorted.sumOf { value ->
        val delta = value.toDouble() - average
        delta * delta
    } / sorted.size.toDouble()
    return MetricStats(
        count = sorted.size,
        average = average,
        median = median,
        p95 = sorted[p95Index].toDouble(),
        stdDev = sqrt(variance)
    )
}

fun summarizeBenchmarkHistory(history: List<EngineBenchmarkReport>): BenchmarkHistoryStats? {
    // v2.0-v2.2 used different traffic-origin experiments. v2.3 is the first schema where both
    // engines use the same RRBOX socket explicitly bound to Android's VPN Network.
    val reports = history.filter { report ->
        report.benchmarkVersion >= 5 &&
            report.system.proxyPathVerifiedCount >= 2 &&
            report.hev.proxyPathVerifiedCount >= 2 &&
            report.hev.nativePathVerifiedCount >= 2
    }
    if (reports.isEmpty()) return null

    fun summarize(samples: List<EngineBenchmarkSample>): EngineHistoryStats {
        val https = samples
            .flatMap { it.httpsRounds.orEmpty() }
            .filter { it.success && it.proxyPathVerified }
        val udp = samples.flatMap { it.udpRounds.orEmpty() }
        return EngineHistoryStats(
            runs = samples.size,
            restart = calculateMetricStats(samples.map { it.restartMillis }),
            httpsFirstByte = calculateMetricStats(https.mapNotNull { it.firstByteMillis }),
            downloadBps = calculateMetricStats(https.mapNotNull { it.downloadBps }),
            pssKb = calculateMetricStats(samples.map { it.processPssKb.toLong() }),
            udpSuccesses = udp.count { it.success },
            udpAttempts = udp.size,
            proxyVerifiedRounds = https.size,
            nativeVerifiedRounds = https.count { it.nativePathVerified },
            httpsSuccessRounds = https.size
        )
    }

    return BenchmarkHistoryStats(
        runs = reports.size,
        system = summarize(reports.map { it.system }),
        hev = summarize(reports.map { it.hev })
    )
}

fun DiagnosticReport.toPlainText(): String = buildString {
    appendLine("RRBOX Network Lab 诊断报告")
    appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
    appendLine("网络: ${snapshot.transport} / ${snapshot.activeInterface} / MTU ${snapshot.mtu}")
    appendLine("IPv4: ${snapshot.ipv4Addresses.joinToString().ifBlank { "--" }}")
    appendLine("IPv6: ${snapshot.ipv6Addresses.joinToString().ifBlank { "--" }}")
    appendLine("DNS: ${snapshot.dnsServers.joinToString().ifBlank { "--" }}")
    appendLine("VPN TUN: ${snapshot.vpnInterface ?: "--"} / MTU ${snapshot.vpnMtu ?: 0}")
    appendLine()
    checks.forEach { appendLine("[${it.status}] ${it.name}: ${it.detail}") }
}

fun EngineBenchmarkReport.toPlainText(): String = buildString {
    if (benchmarkVersion < 2) {
        appendLine("RRBOX System vs HEV A/B 旧版观察报告")
        appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
        appendLine("节点: $nodeTag ($nodeServerMasked)")
        appendLine("System 重建: ${system.restartMillis} ms")
        appendLine("HEV 重建: ${hev.restartMillis} ms")
        appendLine("说明: 此记录来自 A/B v1，仅保留历史，不用于性能结论。")
        return@buildString
    }

    if (benchmarkVersion < 5) {
        val label = when {
            benchmarkVersion >= 4 -> "v2.2"
            benchmarkVersion >= 3 -> "v2.1"
            else -> "v2"
        }
        appendLine("RRBOX System vs HEV A/B $label 旧实验报告（不纳入 v2.3 统计）")
        appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
        appendLine("节点: $nodeTag ($nodeServerMasked)")
        appendLine("System 重建: ${system.restartMillis} ms")
        appendLine("HEV 重建: ${hev.restartMillis} ms")
        appendLine("说明: 旧实验的测试流量来源/路由方式与 v2.3 不同，仅保留查看。")
        return@buildString
    }

    appendLine("RRBOX System vs HEV A/B v2.3 实测报告")
    appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
    appendLine("节点: $nodeTag ($nodeServerMasked)")
    appendLine("原始引擎: $originalEngine")
    appendLine("测速绑定: ${helperPackage.ifBlank { "Android VPN Network.socketFactory" }}")
    appendLine("代理路径预检: PASS（每个引擎先做 64 KiB；未通过不会生成本报告）")
    appendLine("HTTPS 固定下载: $probeTarget / 每轮 ${formatBytesForReport(system.downloadBytesPerRound)} × 3")
    appendLine("UDP: v2.3 暂不纳入 A/B")
    appendLine()

    listOf(system, hev).forEach { sample ->
        appendLine("[${sample.engine}]")
        appendLine("重建耗时: ${sample.restartMillis} ms")
        appendLine("HTTPS 成功: ${sample.httpsSuccessCount}/${sample.httpsAttemptCount}")
        appendLine("有效代理轮次: ${sample.proxyPathVerifiedCount}/${sample.httpsAttemptCount}")
        appendLine("DNS 中位数: ${sample.httpsDnsMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("客户端 TCP connect 中位数: ${sample.httpsTcpMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("TLS 中位数: ${sample.httpsTlsMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("HTTPS 首字节中位数: ${sample.httpsFirstByteMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("固定下载中位数: ${sample.httpsDownloadMedianBps?.let(::formatRateForReport) ?: "--"}")
        appendLine("sing-box 流量计数验证: ${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}")
        if (sample.engine.equals("HEV", ignoreCase = true)) {
            appendLine("HEV native TUN RX 验证: ${sample.nativePathVerifiedCount}/${sample.httpsSuccessCount}")
        }
        appendLine("RRBOX 进程 CPU: ${sample.processCpuMillis} ms / PSS ${String.format("%.1f", sample.processPssKb / 1024.0)} MB")

        sample.httpsRounds.orEmpty().forEach { round ->
            if (round.success) {
                appendLine(
                    "  HTTPS #${round.attempt}: network=${round.protocol.orEmpty()} " +
                        "DNS=${round.dnsMillis ?: -1}ms TCP=${round.tcpConnectMillis ?: -1}ms " +
                        "TLS=${round.tlsMillis ?: -1}ms TTFB=${round.firstByteMillis ?: -1}ms " +
                        "rate=${round.downloadBps?.let(::formatRateForReport) ?: "--"} " +
                        "proxyCount=${formatBytesForReport(round.proxyAccountedDownloadBytes)} " +
                        if (sample.engine.equals("HEV", ignoreCase = true)) {
                            "nativeRx=${formatBytesForReport(round.nativeAccountedDownloadBytes)} " +
                                "nativeVerified=${if (round.nativePathVerified) "yes" else "no"} "
                        } else {
                            ""
                        } +
                        "verified=${if (round.proxyPathVerified) "yes" else "no"}"
                )
            } else {
                appendLine(
                    "  HTTPS #${round.attempt}: FAIL network=${round.protocol.orEmpty()} ${round.error.orEmpty()}"
                )
            }
        }
        appendLine()
    }

    appendLine(
        "说明: A/B v2.3 完全恢复 RRBOX 已验证的正常 System/HEV 启动路径，不修改 UID 列表、HEV self-bypass 或 TUN 路由。" +
            "两套引擎都由 RRBOX 的同一 OkHttp 探针发起请求，但 DNS 与 socket 都显式绑定到 Android 当前 TRANSPORT_VPN Network。" +
            "System 使用 sing-box sessionTraffic 验证路径，HEV 同时要求 sessionTraffic 与 hev-socks5-tunnel native RX 字节双重通过。"
    )
}

private fun medianLong(values: List<Long>): Long? = calculateMetricStats(values)?.median?.roundToLong()

private fun formatRateForReport(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> String.format("%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    bytesPerSecond >= 1024L -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}

private fun formatBytesForReport(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.2f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

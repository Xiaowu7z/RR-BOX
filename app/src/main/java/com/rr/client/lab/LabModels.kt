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
    val accountingSettleMillis: Long = 0L,
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
    val baselinePssKb: Int = 0,
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

    val pssGrowthKb: Int
        get() = if (baselinePssKb > 0) processPssKb - baselinePssKb else 0

    val udpAttemptCount: Int
        get() = udpRounds.orEmpty().size

    val udpSuccessCount: Int
        get() = udpRounds.orEmpty().count { it.success }

    val udpMedianRttMillis: Long?
        get() = medianLong(udpRounds.orEmpty().mapNotNull { if (it.success) it.rttMillis else null })
}

data class EngineBenchmarkReport(
    val benchmarkVersion: Int = 8,
    val timestamp: Long = System.currentTimeMillis(),
    val nodeTag: String,
    val nodeServerMasked: String,
    val originalEngine: String,
    val probeTarget: String = "speed.cloudflare.com",
    val helperPackage: String = "",
    val udpTarget: String = "",
    val executionOrder: String? = null,
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
    val httpsSuccessRounds: Int,
    val tlsMillis: MetricStats? = null,
    val cpuMillis: MetricStats? = null,
    val pssDeltaKb: MetricStats? = null
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
    // v2.6 keeps the proven v2.5 traffic path but adds settled accounting and alternating engine
    // order. Only v2.6+ records are mixed so the new memory/order methodology remains comparable.
    val reports = history.filter { report ->
        report.benchmarkVersion >= 8 &&
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
        val baselinePss = samples.map { sample ->
            (if (sample.baselinePssKb > 0) sample.baselinePssKb else sample.processPssKb).toLong()
        }
        val pssDeltas = samples
            .filter { it.baselinePssKb > 0 }
            .map { it.pssGrowthKb.toLong() }
        return EngineHistoryStats(
            runs = samples.size,
            restart = calculateMetricStats(samples.map { it.restartMillis }),
            httpsFirstByte = calculateMetricStats(https.mapNotNull { it.firstByteMillis }),
            downloadBps = calculateMetricStats(https.mapNotNull { it.downloadBps }),
            pssKb = calculateMetricStats(baselinePss),
            udpSuccesses = udp.count { it.success },
            udpAttempts = udp.size,
            proxyVerifiedRounds = https.size,
            nativeVerifiedRounds = https.count { it.nativePathVerified },
            httpsSuccessRounds = https.size,
            tlsMillis = calculateMetricStats(https.mapNotNull { it.tlsMillis }),
            cpuMillis = calculateMetricStats(samples.map { it.processCpuMillis }),
            pssDeltaKb = calculateMetricStats(pssDeltas)
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

    if (benchmarkVersion < 8) {
        val label = when {
            benchmarkVersion >= 7 -> "v2.5"
            benchmarkVersion >= 6 -> "v2.4"
            benchmarkVersion >= 5 -> "v2.3"
            benchmarkVersion >= 4 -> "v2.2"
            benchmarkVersion >= 3 -> "v2.1"
            else -> "v2"
        }
        appendLine("RRBOX System vs HEV A/B $label 旧实验报告（不纳入 v2.6 统计）")
        appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
        appendLine("节点: $nodeTag ($nodeServerMasked)")
        appendLine("System 重建: ${system.restartMillis} ms")
        appendLine("HEV 重建: ${hev.restartMillis} ms")
        appendLine("说明: v2.6 开始使用稳定落账、交替测试顺序和 PSS 基线/增量，因此旧记录仅保留查看。")
        return@buildString
    }

    appendLine("RRBOX System vs HEV A/B v2.6 实测报告")
    appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
    appendLine("节点: $nodeTag ($nodeServerMasked)")
    appendLine("原始引擎: $originalEngine")
    appendLine("本轮顺序: ${executionOrder.orEmpty().ifBlank { "--" }}（成功的 v2.6 记录会自动交替顺序）")
    appendLine("测速路径: ${helperPackage.ifBlank { "RRBOX UID natural VPN routing + fixed IPv4 bootstrap + settled accounting" }}")
    appendLine("DNS: 启动前固定解析一次 IPv4，两套引擎复用同一目标；DNS 不参与 A/B 成绩")
    appendLine("HEV 测试态: 仅本轮临时纳入 RRBOX UID；不修改 127/8；sing-box 远端 socket 继续 protect(fd)")
    appendLine("计数落账: HTTPS 完成后继续等待 sessionTraffic/native RX 稳定至少 1.4 秒，再进入下一轮")
    appendLine("代理路径预检: PASS（每个引擎先做 64 KiB；未通过不会生成本报告）")
    appendLine("HTTPS 固定下载: $probeTarget / 每轮 ${formatBytesForReport(system.downloadBytesPerRound)} × 3")
    appendLine("UDP: v2.6 暂不纳入 A/B")
    appendLine()

    listOf(system, hev).forEach { sample ->
        appendLine("[${sample.engine}]")
        appendLine("重建耗时: ${sample.restartMillis} ms")
        appendLine("HTTPS 成功: ${sample.httpsSuccessCount}/${sample.httpsAttemptCount}")
        appendLine("有效代理轮次: ${sample.proxyPathVerifiedCount}/${sample.httpsAttemptCount}")
        appendLine("应用侧 TCP connect 中位数: ${sample.httpsTcpMedianMillis?.let { "$it ms" } ?: "--"}（仅本地建连参考，不当作远端 RTT）")
        appendLine("TLS 中位数: ${sample.httpsTlsMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("HTTPS 首字节中位数: ${sample.httpsFirstByteMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("固定下载中位数: ${sample.httpsDownloadMedianBps?.let(::formatRateForReport) ?: "--"}")
        appendLine("sing-box 流量计数验证: ${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}")
        if (sample.engine.equals("HEV", ignoreCase = true)) {
            appendLine("HEV native TUN RX 验证: ${sample.nativePathVerifiedCount}/${sample.httpsSuccessCount}")
        }
        val baseline = if (sample.baselinePssKb > 0) sample.baselinePssKb else sample.processPssKb
        appendLine("RRBOX 进程 CPU: ${sample.processCpuMillis} ms")
        appendLine(
            "PSS: ${formatPssMb(baseline)} → ${formatPssMb(sample.processPssKb)} " +
                "(Δ ${formatSignedPssMb(sample.processPssKb - baseline)})"
        )

        sample.httpsRounds.orEmpty().forEach { round ->
            if (round.success) {
                appendLine(
                    "  HTTPS #${round.attempt}: network=${round.protocol.orEmpty()} " +
                        "TCP=${round.tcpConnectMillis ?: -1}ms TLS=${round.tlsMillis ?: -1}ms " +
                        "TTFB=${round.firstByteMillis ?: -1}ms " +
                        "rate=${round.downloadBps?.let(::formatRateForReport) ?: "--"} " +
                        "proxyCount=${formatAccounting(round.proxyAccountedDownloadBytes, round.bytesReceived)} " +
                        if (sample.engine.equals("HEV", ignoreCase = true)) {
                            "nativeRx=${formatAccounting(round.nativeAccountedDownloadBytes, round.bytesReceived)} " +
                                "nativeVerified=${if (round.nativePathVerified) "yes" else "no"} "
                        } else {
                            ""
                        } +
                        "settle=${round.accountingSettleMillis}ms " +
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
        "说明: A/B v2.6 保留已通过真机验证的 v2.5 数据路径，只优化测量方法。System 按正常模式运行；HEV 实验态临时取消 RRBOX self-bypass，" +
            "测试结束后强制恢复正常策略。每轮流量计数会等待状态流稳定后再落账，避免跨轮串账；成功的 v2.6 测试会交替 HEV→SYSTEM / SYSTEM→HEV 顺序，" +
            "并记录 PSS 基线与测试后增量。单次报告仍可能受公网波动影响，建议至少完成 2 次交替顺序后再以历史中位数/P95 判断性能。"
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

private fun formatAccounting(bytes: Long, payloadBytes: Long): String {
    if (payloadBytes <= 0L) return formatBytesForReport(bytes)
    val percent = bytes.toDouble() * 100.0 / payloadBytes.toDouble()
    return "${formatBytesForReport(bytes)} (${String.format("%.0f", percent)}%)"
}

private fun formatPssMb(kb: Int): String = String.format("%.1f MB", kb / 1024.0)

private fun formatSignedPssMb(kb: Int): String {
    val mb = kb / 1024.0
    return if (mb >= 0.0) String.format("+%.1f MB", mb) else String.format("%.1f MB", mb)
}

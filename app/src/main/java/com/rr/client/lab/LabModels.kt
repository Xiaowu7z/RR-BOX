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
    val benchmarkVersion: Int = 3,
    val timestamp: Long = System.currentTimeMillis(),
    val nodeTag: String,
    val nodeServerMasked: String,
    val originalEngine: String,
    val probeTarget: String = "speed.cloudflare.com",
    val udpTarget: String = "stun.l.google.com:19302",
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
    // v2.0 could produce apparently successful HEV rounds that actually bypassed the HEV TUN.
    // Only v2.1/schema-v3 reports with at least two verified rounds per engine are eligible.
    val reports = history.filter { report ->
        report.benchmarkVersion >= 3 &&
            report.system.proxyPathVerifiedCount >= 2 &&
            report.hev.proxyPathVerifiedCount >= 2
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

    val v21 = benchmarkVersion >= 3
    appendLine(
        if (v21) {
            "RRBOX System vs HEV A/B v2.1 实测报告"
        } else {
            "RRBOX System vs HEV A/B v2 实测报告（旧路径校验规则）"
        }
    )
    appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
    appendLine("节点: $nodeTag ($nodeServerMasked)")
    appendLine("原始引擎: $originalEngine")
    if (v21) {
        appendLine("代理路径预检: PASS（每个引擎先做 64 KiB HTTPS；未通过不会生成本报告）")
    }
    appendLine("HTTPS 固定下载: $probeTarget / 每轮 ${formatBytesForReport(system.downloadBytesPerRound)} × 3")
    appendLine("UDP STUN: $udpTarget / 3 次")
    appendLine()

    listOf(system, hev).forEach { sample ->
        appendLine("[${sample.engine}]")
        appendLine("重建耗时: ${sample.restartMillis} ms")
        if (!v21) {
            appendLine("原始 ICMP 参考: ${sample.rawIcmpMillis?.let { "$it ms" } ?: "超时/不可用"}（不参与引擎结论）")
        }
        appendLine("HTTPS 成功: ${sample.httpsSuccessCount}/${sample.httpsAttemptCount}")
        appendLine("有效代理轮次: ${sample.proxyPathVerifiedCount}/${sample.httpsAttemptCount}")
        appendLine("DNS 中位数: ${sample.httpsDnsMedianMillis?.let { "$it ms" } ?: "缓存/未触发"}")
        appendLine("客户端 TCP connect 中位数: ${sample.httpsTcpMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("TLS 中位数: ${sample.httpsTlsMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("HTTPS 首字节中位数: ${sample.httpsFirstByteMedianMillis?.let { "$it ms" } ?: "--"}")
        appendLine("固定下载中位数: ${sample.httpsDownloadMedianBps?.let(::formatRateForReport) ?: "--"}")
        appendLine("代理流量计数验证: ${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}")
        appendLine(
            "UDP STUN: ${sample.udpSuccessCount}/${sample.udpAttemptCount}，中位 RTT " +
                "${sample.udpMedianRttMillis?.let { "$it ms" } ?: "--"}" +
                if (v21) "（共享已验证的 RRBOX UID VPN 路径）" else ""
        )
        appendLine("进程 CPU: ${sample.processCpuMillis} ms / PSS ${String.format("%.1f", sample.processPssKb / 1024.0)} MB")

        sample.httpsRounds.orEmpty().forEach { round ->
            if (round.success) {
                appendLine(
                    "  HTTPS #${round.attempt}: TTFB=${round.firstByteMillis ?: -1}ms " +
                        "rate=${round.downloadBps?.let(::formatRateForReport) ?: "--"} " +
                        "proxyCount=${formatBytesForReport(round.proxyAccountedDownloadBytes)} " +
                        "verified=${if (round.proxyPathVerified) "yes" else "no"}"
                )
            } else {
                appendLine("  HTTPS #${round.attempt}: FAIL ${round.error.orEmpty()}")
            }
        }
        sample.udpRounds.orEmpty().forEach { round ->
            if (round.success) {
                appendLine("  UDP #${round.attempt}: ${round.rttMillis ?: -1}ms ${round.address.orEmpty()}")
            } else {
                appendLine("  UDP #${round.attempt}: FAIL ${round.error.orEmpty()}")
            }
        }
        appendLine()
    }

    if (v21) {
        appendLine(
            "说明: A/B v2.1 在每个引擎正式测试前先用 64 KiB HTTPS 与 sessionTraffic 做硬性路径校验。" +
                "HEV 仅在实验室测试重建期间临时把 RRBOX 自身 UID 纳入 TUN，并显式排除 127/8，" +
                "从而测到 TUN → lwIP → SOCKS5 → sing-box；正常 HEV 模式仍保持 RRBOX 自身绕过，测试结束或取消后会强制按正常模式恢复原始引擎。" +
                "原始 ICMP 已从 v2.1 引擎成绩移除。"
        )
    } else {
        appendLine(
            "说明: A/B v2 旧记录的 HEV 自身 UID 可能绕过 HEV TUN，因此仅保留历史查看；" +
                "v2.1 历史统计不会纳入这些旧记录。"
        )
    }
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

package com.rr.client.lab

import java.text.DateFormat
import java.util.Date

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

data class EngineBenchmarkSample(
    val engine: String,
    val restartMillis: Long,
    val pingMillis: Long?,
    val observedAverageDownloadBps: Long,
    val observedAverageUploadBps: Long,
    val observedPeakDownloadBps: Long,
    val observedPeakUploadBps: Long,
    val trafficDownloadDelta: Long,
    val trafficUploadDelta: Long,
    val processCpuMillis: Long,
    val processPssKb: Int,
    val observationSeconds: Int
)

data class EngineBenchmarkReport(
    val timestamp: Long = System.currentTimeMillis(),
    val nodeTag: String,
    val nodeServerMasked: String,
    val originalEngine: String,
    val system: EngineBenchmarkSample,
    val hev: EngineBenchmarkSample
)

data class LabLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val channel: String,
    val message: String
)

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
    appendLine("RRBOX System vs HEV A/B 观察报告")
    appendLine("时间: ${DateFormat.getDateTimeInstance().format(Date(timestamp))}")
    appendLine("节点: $nodeTag ($nodeServerMasked)")
    appendLine("原始引擎: $originalEngine")
    appendLine()
    listOf(system, hev).forEach { sample ->
        appendLine("[${sample.engine}]")
        appendLine("重建耗时: ${sample.restartMillis} ms")
        appendLine("ICMP Ping: ${sample.pingMillis?.let { "$it ms" } ?: "超时/不可用"}")
        appendLine("观察平均下载: ${sample.observedAverageDownloadBps} B/s")
        appendLine("观察平均上传: ${sample.observedAverageUploadBps} B/s")
        appendLine("观察峰值下载: ${sample.observedPeakDownloadBps} B/s")
        appendLine("观察峰值上传: ${sample.observedPeakUploadBps} B/s")
        appendLine("窗口流量: ↓${sample.trafficDownloadDelta} B ↑${sample.trafficUploadDelta} B")
        appendLine("进程 CPU: ${sample.processCpuMillis} ms / PSS ${sample.processPssKb} KB")
        appendLine("观察窗口: ${sample.observationSeconds}s")
        appendLine()
    }
    appendLine("说明: 当前 A/B 为旁路运行态观察，不主动制造大流量；不会改写 System/HEV 数据面实现。")
}

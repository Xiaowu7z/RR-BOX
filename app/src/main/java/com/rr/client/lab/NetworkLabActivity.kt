package com.rr.client.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rr.client.RRApplication
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.storage.PreferencesManager
import com.rr.client.subscription.SubscriptionParser
import com.rr.client.subscription.model.SubProfile
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSampler
import com.rr.client.traffic.TrafficSpeed
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.DarkSurfaceVariant
import com.rr.client.ui.theme.RRClientTheme
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary
import com.rr.client.vpn.RRVpnService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class NetworkLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RRClientTheme {
                NetworkLabRoot(onBack = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        RRLogCapture.start()
    }

    override fun onStop() {
        RRLogCapture.stop()
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkLabRoot(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = RRApplication.instance.preferencesManager
    val engine by preferences.tunEngine.collectAsState(initial = PreferencesManager.TUN_ENGINE_SYSTEM)
    val selectedNodeId by preferences.selectedNodeId.collectAsState(initial = null)
    val nodeOverrides by preferences.nodeOverrides.collectAsState(initial = emptyMap())
    val isRunning by RRVpnService.isRunning.collectAsState()
    val isStarting by RRVpnService.isStarting.collectAsState()
    val currentSpeed by RRVpnService.currentSpeed.collectAsState()
    val sessionTraffic by RRVpnService.sessionTraffic.collectAsState()
    val selfCheck by StartupSelfCheck.report.collectAsState()
    val logEntries by RRLogStore.entries.collectAsState()

    var profiles by remember { mutableStateOf<List<SubProfile>>(emptyList()) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var diagnostics by remember { mutableStateOf<DiagnosticReport?>(null) }
    var diagnosticBusy by remember { mutableStateOf(false) }
    var benchmark by remember { mutableStateOf<EngineBenchmarkReport?>(null) }
    var benchmarkBusy by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableStateOf<String?>(null) }
    var benchmarkError by remember { mutableStateOf<String?>(null) }
    var benchmarkHistory by remember { mutableStateOf(BenchmarkHistoryStore.load(context)) }
    var showRawDialog by remember { mutableStateOf(false) }
    var rawValidation by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            RRApplication.instance.database.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
        }
        if (StartupSelfCheck.report.value == null) StartupSelfCheck.schedule(context)
    }

    val baseNodes = remember(profiles) { profiles.flatMap { it.nodes } }
    val resolvedNodes = remember(baseNodes, nodeOverrides) {
        baseNodes.map { nodeOverrides[it.id] ?: it }
    }
    val selectedNode = resolvedNodes.firstOrNull { it.id == selectedNodeId } ?: resolvedNodes.firstOrNull()

    fun refreshDiagnostics() {
        if (diagnosticBusy) return
        diagnosticBusy = true
        scope.launch {
            diagnostics = runCatching {
                NetworkDiagnostics.collect(context, selectedNode, engine, isRunning)
            }.onFailure { RRLogStore.record("DIAG", "诊断失败: ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
            diagnosticBusy = false
        }
    }

    LaunchedEffect(selectedNode?.id) {
        if (diagnostics == null) refreshDiagnostics()
    }

    fun copyText(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }

    fun shareText(subject: String, text: String) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    "导出 RRBOX 报告"
                )
            )
        }.onFailure {
            Toast.makeText(context, "无法打开分享面板", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("RRBOX Network Lab", color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            TabRow(selectedTabIndex = selectedTab, containerColor = DarkSurface, contentColor = CyanPrimary) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("实验室") },
                    icon = { Icon(Icons.Default.Science, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("日志") },
                    icon = { Icon(Icons.Default.Description, contentDescription = null) }
                )
            }
            HorizontalDivider(color = CardBorder)
            Spacer(Modifier.height(6.dp).background(DarkBackground))

            if (selectedTab == 0) {
                LabDashboard(
                    engine = engine,
                    isRunning = isRunning,
                    isStarting = isStarting,
                    selectedNode = selectedNode,
                    currentSpeed = currentSpeed,
                    sessionTraffic = sessionTraffic,
                    diagnostics = diagnostics,
                    diagnosticBusy = diagnosticBusy,
                    selfCheck = selfCheck,
                    benchmark = benchmark,
                    benchmarkBusy = benchmarkBusy,
                    benchmarkProgress = benchmarkProgress,
                    benchmarkError = benchmarkError,
                    benchmarkHistory = benchmarkHistory,
                    rawValidation = rawValidation,
                    onRefreshDiagnostics = ::refreshDiagnostics,
                    onRunBenchmark = {
                        val node = selectedNode
                        if (node != null && !benchmarkBusy) {
                            benchmarkBusy = true
                            benchmark = null
                            benchmarkError = null
                            benchmarkProgress = "准备 A/B v2.1"
                            scope.launch {
                                runCatching {
                                    EngineBenchmarkRunner(
                                        context = context,
                                        preferences = preferences,
                                        node = node,
                                        onProgress = { benchmarkProgress = it }
                                    ).run()
                                }.onSuccess { result ->
                                    benchmark = result
                                    benchmarkHistory = BenchmarkHistoryStore.load(context)
                                    diagnostics = NetworkDiagnostics.collect(
                                        context,
                                        node,
                                        preferences.tunEngine.first(),
                                        RRVpnService.isRunning.value
                                    )
                                }.onFailure { error ->
                                    benchmarkError = error.message ?: error.javaClass.simpleName
                                    RRLogStore.record("BENCH", "A/B v2.1 失败: ${benchmarkError.orEmpty()}")
                                }
                                benchmarkBusy = false
                                benchmarkProgress = null
                            }
                        }
                    },
                    onCopyDiagnostics = { diagnostics?.let { copyText("RRBOX diagnostics", it.toPlainText()) } },
                    onShareDiagnostics = { diagnostics?.let { shareText("RRBOX diagnostics", it.toPlainText()) } },
                    onCopyBenchmark = { benchmark?.let { copyText("RRBOX benchmark", it.toPlainText()) } },
                    onShareBenchmark = { benchmark?.let { shareText("RRBOX benchmark", it.toPlainText()) } },
                    onClearBenchmarkHistory = {
                        BenchmarkHistoryStore.clear(context)
                        benchmarkHistory = emptyList()
                    },
                    onOpenRawValidator = { showRawDialog = true }
                )
            } else {
                LogCenter(
                    entries = logEntries,
                    onCopy = { copyText("RRBOX logs", RRLogStore.exportText()) },
                    onShare = { shareText("RRBOX logs", RRLogStore.exportText()) },
                    onClear = RRLogStore::clear
                )
            }
        }
    }

    if (showRawDialog) {
        RawOutboundDialog(
            validation = rawValidation,
            onDismiss = { showRawDialog = false },
            onValidate = { raw ->
                scope.launch {
                    rawValidation = withContext(Dispatchers.Default) {
                        validateRawOutbound(raw)
                    }
                }
            }
        )
    }
}

@Composable
private fun LabDashboard(
    engine: String,
    isRunning: Boolean,
    isStarting: Boolean,
    selectedNode: ProxyNode?,
    currentSpeed: TrafficSpeed,
    sessionTraffic: SessionTraffic,
    diagnostics: DiagnosticReport?,
    diagnosticBusy: Boolean,
    selfCheck: SelfCheckReport?,
    benchmark: EngineBenchmarkReport?,
    benchmarkBusy: Boolean,
    benchmarkProgress: String?,
    benchmarkError: String?,
    benchmarkHistory: List<EngineBenchmarkReport>,
    rawValidation: String?,
    onRefreshDiagnostics: () -> Unit,
    onRunBenchmark: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onCopyBenchmark: () -> Unit,
    onShareBenchmark: () -> Unit,
    onClearBenchmarkHistory: () -> Unit,
    onOpenRawValidator: () -> Unit
) {
    val historyStats = remember(benchmarkHistory) { summarizeBenchmarkHistory(benchmarkHistory) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LabCard(title = "运行状态", icon = { Icon(Icons.Default.Memory, null, tint = CyanPrimary) }) {
            StatusRow("转发引擎", if (engine == PreferencesManager.TUN_ENGINE_HEV) "HEV / Experimental" else "System / Stable")
            StatusRow("VPN", when { isStarting -> "正在重建"; isRunning -> "已连接"; else -> "未连接" })
            StatusRow("节点", selectedNode?.tag ?: "未选择")
            StatusRow("实时下载", currentSpeed.formattedDownSpeed)
            StatusRow("实时上传", currentSpeed.formattedUpSpeed)
            StatusRow("本次流量", "↓ ${TrafficSampler.formatBytes(sessionTraffic.proxyDownloadTotal)}  ↑ ${TrafficSampler.formatBytes(sessionTraffic.proxyUploadTotal)}")
            StatusRow("连接时长", formatDuration(sessionTraffic.durationSeconds))
        }

        LabCard(title = "网络路径诊断", icon = { Icon(Icons.Default.NetworkCheck, null, tint = CyanPrimary) }) {
            val snapshot = diagnostics?.snapshot
            if (snapshot != null) {
                StatusRow("物理网络", "${snapshot.transport} · ${snapshot.activeInterface}")
                StatusRow("MTU", snapshot.mtu.toString())
                StatusRow("IPv4", snapshot.ipv4Addresses.joinToString().ifBlank { "--" })
                StatusRow("IPv6", snapshot.ipv6Addresses.joinToString().ifBlank { "--" })
                StatusRow("DNS", snapshot.dnsServers.joinToString().ifBlank { "--" })
                StatusRow("VPN TUN", snapshot.vpnInterface?.let { "$it · MTU ${snapshot.vpnMtu ?: 0}" } ?: "--")
                Spacer(Modifier.height(8.dp))
                diagnostics.checks.forEach { CheckLine(it) }
            } else {
                Text("尚未生成诊断报告", color = TextSecondary)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRefreshDiagnostics,
                    enabled = !diagnosticBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) { Text(if (diagnosticBusy) "诊断中…" else "一键诊断", color = DarkBackground) }
                OutlinedButton(onClick = onCopyDiagnostics, enabled = diagnostics != null) {
                    Icon(Icons.Default.ContentCopy, null)
                    Text("复制")
                }
                OutlinedButton(onClick = onShareDiagnostics, enabled = diagnostics != null) {
                    Icon(Icons.Default.Share, null)
                    Text("导出")
                }
            }
        }

        LabCard(title = "System vs HEV A/B v2.1", icon = { Icon(Icons.Default.Science, null, tint = CyanPrimary) }) {
            Text(
                "每套引擎先做 64 KiB HTTPS 代理路径预检，只有 sessionTraffic 确认流量进入代理数据面后，才继续 3 轮固定 2 MiB HTTPS 和 3 次 UDP STUN。HEV 仅在本次实验重建期间临时让 RRBOX 自身进入 TUN，并把 127/8 排除在 VPN 外防止本地 SOCKS 回环；测试结束或取消后强制恢复正常引擎模式。原始 ICMP 已从 A/B 成绩移除。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRunBenchmark,
                enabled = isRunning && !isStarting && selectedNode != null && !benchmarkBusy,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(if (benchmarkBusy) "A/B v2.1 测试中…" else "开始一键 A/B v2.1", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
            benchmarkProgress?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = CyanPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (!isRunning) {
                Text("请先在主界面连接一个节点再运行 A/B。", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            benchmarkError?.let { Text("失败：$it", color = MaterialTheme.colorScheme.error) }

            benchmark?.let { report ->
                Spacer(Modifier.height(12.dp))
                BenchmarkSampleCard(report.system)
                Spacer(Modifier.height(8.dp))
                BenchmarkSampleCard(report.hev)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyBenchmark) { Text("复制报告") }
                    OutlinedButton(onClick = onShareBenchmark) { Text("导出报告") }
                }
            }

            historyStats?.takeIf { it.runs >= 2 }?.let { stats ->
                Spacer(Modifier.height(12.dp))
                Text("A/B v2.1 历史统计 · ${stats.runs} 次", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                HistoryStatsCard("SYSTEM", stats.system)
                Spacer(Modifier.height(6.dp))
                HistoryStatsCard("HEV", stats.hev)
            }

            if (benchmarkHistory.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("历史记录 ${benchmarkHistory.size} 条", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                benchmarkHistory.take(3).forEach { item ->
                    val summary = when {
                        item.benchmarkVersion >= 3 -> {
                            "${item.nodeTag}: v2.1 · System ${item.system.httpsFirstByteMedianMillis ?: -1}ms / HEV ${item.hev.httpsFirstByteMedianMillis ?: -1}ms TTFB"
                        }
                        item.benchmarkVersion >= 2 -> {
                            "${item.nodeTag}: v2 旧路径记录 · 不进入正式统计"
                        }
                        else -> {
                            "${item.nodeTag}: v1 旧版 · System ${item.system.restartMillis}ms / HEV ${item.hev.restartMillis}ms"
                        }
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                TextButton(onClick = onClearBenchmarkHistory) { Text("清空 A/B 历史") }
            }
        }

        LabCard(title = "启动自检", icon = { Icon(Icons.Default.BugReport, null, tint = CyanPrimary) }) {
            if (selfCheck == null) {
                Text("自检正在后台执行…", color = TextSecondary)
            } else {
                selfCheck.checks.forEach { CheckLine(it) }
            }
        }

        LabCard(title = "Raw sing-box Outbound", icon = { Icon(Icons.Default.Description, null, tint = CyanPrimary) }) {
            Text(
                "RRBOX 的节点导入器已经原生支持单个 outbound JSON、outbound 数组和完整 sing-box config。这里提供高级预校验；验证通过后可在「节点 → + → 粘贴 / JSON」用同一导入链保存，不改稳定 ConfigBuilder 路径。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenRawValidator) { Text("打开 Raw Outbound 校验器") }
            rawValidation?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun LogCenter(
    entries: List<LabLogEntry>,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("日志中心", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text("仅在 Network Lab 页面打开期间采集 RRBOX 自身进程日志，并自动隐藏 UUID、token、password、private_key 等敏感字段。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, null)
                Text("复制")
            }
            OutlinedButton(onClick = onShare, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.Share, null)
                Text("导出")
            }
            OutlinedButton(onClick = onClear, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, null)
                Text("清空")
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries.takeLast(300).reversed(), key = { "${it.timestamp}-${it.hashCode()}" }) { entry ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurface,
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(entry.channel, color = CyanPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(entry.message, color = TextSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabCard(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.weight(0.36f),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            modifier = Modifier.weight(0.64f),
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun CheckLine(check: LabCheck) {
    val symbol = when (check.status) {
        LabCheckStatus.PASS -> "✓"
        LabCheckStatus.WARN -> "!"
        LabCheckStatus.FAIL -> "×"
        LabCheckStatus.INFO -> "·"
    }
    Text(
        "$symbol ${check.name}: ${check.detail}",
        color = if (check.status == LabCheckStatus.FAIL) MaterialTheme.colorScheme.error else TextSecondary,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun BenchmarkSampleCard(sample: EngineBenchmarkSample) {
    Surface(shape = RoundedCornerShape(10.dp), color = DarkSurfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(sample.engine, color = CyanPrimary, fontWeight = FontWeight.Bold)
            StatusRow("路径预检", "PASS · 64 KiB")
            StatusRow("重建耗时", "${sample.restartMillis} ms")
            StatusRow("HTTPS 成功", "${sample.httpsSuccessCount}/${sample.httpsAttemptCount}")
            StatusRow("有效代理轮次", "${sample.proxyPathVerifiedCount}/${sample.httpsAttemptCount}")
            StatusRow("DNS 中位", sample.httpsDnsMedianMillis?.let { "$it ms" } ?: "缓存/未触发")
            StatusRow("客户端 TCP", sample.httpsTcpMedianMillis?.let { "$it ms" } ?: "--")
            StatusRow("TLS 中位", sample.httpsTlsMedianMillis?.let { "$it ms" } ?: "--")
            StatusRow("HTTPS 首字节", sample.httpsFirstByteMedianMillis?.let { "$it ms" } ?: "--")
            StatusRow("2 MiB 下载中位", sample.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--")
            StatusRow("代理计数验证", "${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}")
            StatusRow(
                "UDP STUN",
                "${sample.udpSuccessCount}/${sample.udpAttemptCount}" +
                    (sample.udpMedianRttMillis?.let { " · ${it} ms" } ?: "") +
                    " · 路径已验证"
            )
            StatusRow("进程 CPU", "${sample.processCpuMillis} ms")
            StatusRow("PSS", String.format("%.1f MB", sample.processPssKb / 1024.0))

            if (sample.proxyPathVerifiedCount < 2) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "有效代理轮次不足，当前样本不会进入 v2.1 正式历史统计。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun HistoryStatsCard(label: String, stats: EngineHistoryStats) {
    Surface(shape = RoundedCornerShape(10.dp), color = DarkSurfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(label, color = CyanPrimary, fontWeight = FontWeight.Bold)
            stats.restart?.let { StatusRow("重建", metricMillis(it)) }
            stats.httpsFirstByte?.let { StatusRow("HTTPS 首字节", metricMillis(it)) }
            stats.downloadBps?.let { StatusRow("下载", metricSpeed(it)) }
            stats.pssKb?.let {
                StatusRow(
                    "PSS",
                    "中位 ${String.format("%.1f", it.median / 1024.0)} MB · P95 ${String.format("%.1f", it.p95 / 1024.0)} MB"
                )
            }
            StatusRow("UDP 成功率", "${stats.udpSuccesses}/${stats.udpAttempts}")
            StatusRow("代理计数验证", "${stats.proxyVerifiedRounds}/${stats.httpsSuccessRounds}")
        }
    }
}

private fun metricMillis(stats: MetricStats): String =
    "中位 ${stats.median.roundToLong()} ms · P95 ${stats.p95.roundToLong()} · σ ${String.format("%.1f", stats.stdDev)}"

private fun metricSpeed(stats: MetricStats): String =
    "中位 ${TrafficSampler.formatSpeed(stats.median.roundToLong())} · P95 ${TrafficSampler.formatSpeed(stats.p95.roundToLong())}"

@Composable
private fun RawOutboundDialog(
    validation: String?,
    onDismiss: () -> Unit,
    onValidate: (String) -> Unit
) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw sing-box Outbound 校验") },
        text = {
            Column {
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    maxLines = 14,
                    label = { Text("单个 outbound / outbounds[] / 完整 config") }
                )
                validation?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        },
        confirmButton = { Button(onClick = { onValidate(raw) }, enabled = raw.isNotBlank()) { Text("校验") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun validateRawOutbound(raw: String): String {
    if (raw.isBlank()) return "请输入 JSON"
    val parsed = SubscriptionParser.parseContent(raw, SubProfile.LOCAL_PROFILE_ID, SubProfile.LOCAL_PROFILE_NAME)
    if (parsed.isEmpty()) return "未识别到 outbound；请检查 JSON 中是否有 type/server/server_port。"
    val valid = parsed.count { node ->
        runCatching {
            val config = ConfigBuilder.buildSingBoxConfig(
                selectedNode = node,
                allNodes = listOf(node),
                appRoutes = emptyList(),
                smartRouting = false,
                perAppMode = PerAppPolicyResolver.MODE_ALL,
                fastForwarding = false
            )
            Libbox.checkConfig(config)
        }.isSuccess
    }
    RRLogStore.record("RAW", "Raw outbound 校验: parsed=${parsed.size}, valid=$valid")
    return if (valid == parsed.size) {
        "校验通过：$valid/${parsed.size} 个 outbound 可进入 RRBOX 现有 ConfigBuilder。可到节点页直接保存。"
    } else {
        "部分失败：$valid/${parsed.size} 个通过 sing-box 配置校验。"
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

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
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.PreferencesManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class NetworkLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RRClientTheme { NetworkLabRoot(onBack = ::finish) }
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
    var recoveryDrillBusy by remember { mutableStateOf(false) }
    var benchmark by remember { mutableStateOf<EngineBenchmarkReport?>(null) }
    var benchmarkBusy by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableStateOf<String?>(null) }
    var benchmarkError by remember { mutableStateOf<String?>(null) }
    var benchmarkHistory by remember { mutableStateOf(BenchmarkHistoryStore.load(context)) }
    var showRawDialog by remember { mutableStateOf(false) }
    var rawValidation by remember { mutableStateOf<String?>(null) }

    suspend fun reloadProfiles() {
        profiles = withContext(Dispatchers.IO) {
            RRApplication.instance.database.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
        }
    }

    LaunchedEffect(Unit) {
        reloadProfiles()
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
            }.onFailure {
                RRLogStore.record("DIAG", "诊断失败: ${it.message ?: it.javaClass.simpleName}")
            }.getOrNull()
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
                    recoveryDrillBusy = recoveryDrillBusy,
                    selfCheck = selfCheck,
                    benchmark = benchmark,
                    benchmarkBusy = benchmarkBusy,
                    benchmarkProgress = benchmarkProgress,
                    benchmarkError = benchmarkError,
                    benchmarkHistory = benchmarkHistory,
                    rawValidation = rawValidation,
                    onRefreshDiagnostics = ::refreshDiagnostics,
                    onRunRecoveryDrill = {
                        if (!recoveryDrillBusy) {
                            recoveryDrillBusy = true
                            scope.launch {
                                NetworkContinuityObserver.runRecoveryDrill(context)
                                    .onSuccess { message ->
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                    .onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            "恢复演练失败：${error.message ?: error.javaClass.simpleName}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                recoveryDrillBusy = false
                                refreshDiagnostics()
                            }
                        }
                    },
                    onRunBenchmark = {
                        val node = selectedNode
                        if (node != null && !benchmarkBusy) {
                            benchmarkBusy = true
                            benchmark = null
                            benchmarkError = null
                            benchmarkProgress = "准备 System vs HEV A/B"
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
                                    RRLogStore.record("BENCH", "A/B 失败: ${benchmarkError.orEmpty()}")
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
                rawValidation = "正在校验并导入…"
                scope.launch {
                    val result = RawLocalNodeImporter.importRaw(raw)
                    if (result.isSuccess) {
                        val summary = result.getOrThrow()
                        reloadProfiles()
                        rawValidation = summary.message()
                    } else {
                        val error = result.exceptionOrNull()
                        rawValidation = "导入失败：${error?.message ?: error?.javaClass?.simpleName ?: "未知错误"}"
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
    recoveryDrillBusy: Boolean,
    selfCheck: SelfCheckReport?,
    benchmark: EngineBenchmarkReport?,
    benchmarkBusy: Boolean,
    benchmarkProgress: String?,
    benchmarkError: String?,
    benchmarkHistory: List<EngineBenchmarkReport>,
    rawValidation: String?,
    onRefreshDiagnostics: () -> Unit,
    onRunRecoveryDrill: () -> Unit,
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
            StatusRow("转发引擎", if (engine == PreferencesManager.TUN_ENGINE_HEV) "HEV / High Performance" else "System / Stable")
            StatusRow("VPN", when { isStarting -> "正在重建"; isRunning -> "已连接"; else -> "未连接" })
            StatusRow("节点", selectedNode?.tag ?: "未选择")
            StatusRow("实时下载", currentSpeed.formattedDownSpeed)
            StatusRow("实时上传", currentSpeed.formattedUpSpeed)
            StatusRow(
                "本次流量",
                "↓ ${TrafficSampler.formatBytes(sessionTraffic.proxyDownloadTotal)}  ↑ ${TrafficSampler.formatBytes(sessionTraffic.proxyUploadTotal)}"
            )
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
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRunRecoveryDrill,
                enabled = isRunning && !isStarting && !recoveryDrillBusy
            ) {
                Text(if (recoveryDrillBusy) "恢复演练中…" else "运行自动恢复演练")
            }
            Text(
                "演练只临时暂停 RRBOX 本地数据面，不清除节点/配置/连接意图；随后走与真实切网异常完全相同的缓存恢复链。",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        LabCard(title = "System vs HEV A/B · 已验证基准", icon = { Icon(Icons.Default.Science, null, tint = CyanPrimary) }) {
            Text(
                "System 与 HEV 使用同一节点、固定 2 MiB HTTPS 负载和路径计数交叉验证。HEV 正式配置包含 SOCKS5 pipeline 与 best-effort TCP Fast Open；测试结束后自动恢复用户原始引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "该基准用于比较服务内重建、TLS、首字节、2 MiB 下载、CPU/PSS 与真实路径计数。公网结果只代表当前设备和网络，不用于宣称某引擎普遍更快。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRunBenchmark,
                enabled = isRunning && !isStarting && selectedNode != null && !benchmarkBusy,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (benchmarkBusy) "A/B 测试中…" else "开始一键 A/B",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
            benchmarkProgress?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = CyanPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (!isRunning && !benchmarkBusy) {
                Text("请先在主界面连接一个节点再运行 A/B。", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
            benchmarkError?.let {
                Text("失败：$it", color = MaterialTheme.colorScheme.error)
                Text(
                    "A/B 失败或结束后都会恢复测试前的原始转发引擎，不改变日常运行配置。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            benchmark?.let { report ->
                Spacer(Modifier.height(12.dp))
                report.executionOrder?.takeIf(String::isNotBlank)?.let {
                    StatusRow("本轮顺序", it)
                }
                Text("路径 · ${report.helperPackage}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                BenchmarkSampleCard(report.system)
                Spacer(Modifier.height(8.dp))
                BenchmarkSampleCard(report.hev)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyBenchmark) { Text("复制报告") }
                    OutlinedButton(onClick = onShareBenchmark) { Text("导出报告") }
                }
            }

            historyStats?.let { stats ->
                Spacer(Modifier.height(12.dp))
                Text("A/B 历史统计 · ${stats.runs} 次", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                StatusRow(
                    "顺序覆盖",
                    "SYSTEM→HEV ${stats.systemFirstRuns} · HEV→SYSTEM ${stats.hevFirstRuns}"
                )
                Text(
                    if (stats.systemFirstRuns > 0 && stats.hevFirstRuns > 0) {
                        "两个测试顺序都已有有效样本，可优先看历史中位数/P95。"
                    } else {
                        "再完成另一种顺序，可进一步抵消公网时间漂移。"
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(6.dp))
                HistoryStatsCard("SYSTEM", stats.system)
                Spacer(Modifier.height(6.dp))
                HistoryStatsCard("HEV", stats.hev)
            }

            if (benchmarkHistory.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("历史记录 ${benchmarkHistory.size} 条", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                benchmarkHistory.take(5).forEach { item ->
                    val summary = when {
                        item.benchmarkVersion >= 10 ->
                            "${item.nodeTag}: 当前已验证 ${item.executionOrder.orEmpty()} · System ${item.system.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"} / HEV ${item.hev.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--"}"
                        item.benchmarkVersion >= 9 ->
                            "${item.nodeTag}: 旧 HEV 基线 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 8 ->
                            "${item.nodeTag}: 旧测量 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 7 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 6 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 5 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 4 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 3 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        item.benchmarkVersion >= 2 ->
                            "${item.nodeTag}: 旧基准 · 不进入当前 A/B 统计"
                        else ->
                            "${item.nodeTag}: v1 旧版 · 仅保留历史"
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
                "支持单个 outbound、outbounds[] 和完整 sing-box config。现在会先逐个通过 sing-box 1.14 校验；全部通过后直接写入「本地节点」，重复节点自动跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenRawValidator) { Text("Raw 校验并导入") }
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
        Text(
            "仅在 Network Lab 页面打开期间采集 RRBOX 自身进程日志，并自动隐藏 UUID、token、password、private_key 等敏感字段。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
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
            Text(
                if (sample.engine.equals("HEV", ignoreCase = true)) "HEV · 高性能" else sample.engine,
                color = CyanPrimary,
                fontWeight = FontWeight.Bold
            )
            if (sample.engine.equals("HEV", ignoreCase = true)) {
                StatusRow("HEV 握手", "SOCKS5 pipeline + client TFO")
            }
            StatusRow("路径预检", "PASS · 64 KiB UID→TUN")
            StatusRow("服务内重建", "${sample.restartMillis} ms")
            StatusRow("HTTPS", "${sample.httpsSuccessCount}/${sample.httpsAttemptCount}")
            StatusRow("有效代理轮次", "${sample.proxyPathVerifiedCount}/${sample.httpsAttemptCount}")
            StatusRow("DNS", "启动前固定解析 · 不计分")
            StatusRow("应用侧 TCP", sample.httpsTcpMedianMillis?.let { "$it ms · 非远端 RTT" } ?: "--")
            StatusRow("TLS 中位", sample.httpsTlsMedianMillis?.let { "$it ms" } ?: "--")
            StatusRow("HTTPS 首字节", sample.httpsFirstByteMedianMillis?.let { "$it ms" } ?: "--")
            StatusRow("2 MiB 下载中位", sample.httpsDownloadMedianBps?.let(TrafficSampler::formatSpeed) ?: "--")
            StatusRow("sing-box 路径验证", "${sample.proxyPathVerifiedCount}/${sample.httpsSuccessCount}")
            if (sample.engine.equals("HEV", ignoreCase = true)) {
                StatusRow("HEV native RX", "${sample.nativePathVerifiedCount}/${sample.httpsSuccessCount}")
            }
            StatusRow("RRBOX 进程 CPU", "${sample.processCpuMillis} ms")
            val baseline = if (sample.baselinePssKb > 0) sample.baselinePssKb else sample.processPssKb
            StatusRow(
                "PSS 基线→结束",
                String.format(
                    "%.1f → %.1f MB (Δ %+.1f)",
                    baseline / 1024.0,
                    sample.processPssKb / 1024.0,
                    (sample.processPssKb - baseline) / 1024.0
                )
            )

            if (sample.proxyPathVerifiedCount < 2 ||
                (sample.engine.equals("HEV", ignoreCase = true) && sample.nativePathVerifiedCount < 2)
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "路径验证不足，当前样本不会进入有效 A/B 历史统计。",
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
            stats.restart?.let { StatusRow("服务内重建", metricMillis(it)) }
            stats.tlsMillis?.let { StatusRow("TLS", metricMillis(it)) }
            stats.httpsFirstByte?.let { StatusRow("HTTPS 首字节", metricMillis(it)) }
            stats.downloadBps?.let { StatusRow("下载", metricSpeed(it)) }
            stats.cpuMillis?.let { StatusRow("CPU/6MiB", metricMillis(it)) }
            stats.accountingWaitMillis?.let { StatusRow("路径计数等待", metricMillis(it)) }
            stats.pssKb?.let {
                StatusRow(
                    "PSS 基线",
                    "中位 ${String.format("%.1f", it.median / 1024.0)} MB · P95 ${String.format("%.1f", it.p95 / 1024.0)} MB"
                )
            }
            stats.pssDeltaKb?.let {
                StatusRow(
                    "PSS 增量",
                    "中位 ${String.format("%+.1f", it.median / 1024.0)} MB · P95 ${String.format("%+.1f", it.p95 / 1024.0)} MB"
                )
            }
            StatusRow("代理路径验证", "${stats.proxyVerifiedRounds}/${stats.httpsSuccessRounds}")
            if (label.startsWith("HEV")) {
                StatusRow("native RX 验证", "${stats.nativeVerifiedRounds}/${stats.httpsSuccessRounds}")
            }
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
        title = { Text("Raw sing-box 校验并导入") },
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
        confirmButton = {
            Button(onClick = { onValidate(raw) }, enabled = raw.isNotBlank()) { Text("校验并导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

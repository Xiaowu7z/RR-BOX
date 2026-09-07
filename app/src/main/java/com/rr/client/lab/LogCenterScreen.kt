package com.rr.client.lab

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Activity-scoped state keeps document exports alive across tab changes and rotation. */
class LogCenterViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    var routesOnly by mutableStateOf(saved.get<Boolean>("logRoutesOnly") ?: true)
        private set
    var search by mutableStateOf(saved.get<String>("logSearch") ?: "")
        private set
    var page by mutableStateOf(RRLogPage(emptyList(), 0, null))
        private set
    var pageNumber by mutableIntStateOf(1)
        private set
    var loading by mutableStateOf(false)
        private set
    var actionBusy by mutableStateOf(false)
        private set
    var exportBusy by mutableStateOf(false)
        private set
    var awaitingDocument by mutableStateOf(saved.get<Boolean>("logExportPending") ?: false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var cursors = listOf<Long?>(null)
    private var queryJob: Job? = null
    private var querySequence = 0L
    private var queryInFlight = false
    private var queryIsAutomatic = false
    private var exportJob: Job? = null
    private var liveJob: Job? = null
    private var hasLoaded = false
    var liveUpdates by mutableStateOf(true)
        private set
    var lastReadRevision by mutableLongStateOf(-1L)
        private set

    fun setVisible(visible: Boolean) {
        liveJob?.cancel()
        liveJob = null
        if (!visible) {
            if (queryInFlight) {
                querySequence++
                queryJob?.cancel()
                queryInFlight = false
                loading = false
                if (!queryIsAutomatic) hasLoaded = false
            }
            return
        }
        if (!hasLoaded && !loading) refresh()
        liveJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val state = RRLogStore.state.value
                if (liveUpdates && pageNumber == 1 && search.isBlank() && state.ready &&
                    !queryInFlight && !actionBusy && !exportBusy && state.revision != lastReadRevision) {
                    load(listOf(null), keepCurrent = true)
                }
            }
        }
    }

    fun toggleLiveUpdates() { liveUpdates = !liveUpdates }

    private fun filter() = RRLogFilter(if (routesOnly) ConnectionRouteLog.CHANNEL else null, search.trim())

    fun selectRoutesOnly(value: Boolean) {
        if (value == routesOnly) return
        routesOnly = value
        saved["logRoutesOnly"] = value
        refresh()
    }

    fun updateSearch(value: String) {
        val cleaned = value.take(200)
        if (cleaned == search) return
        search = cleaned
        saved["logSearch"] = cleaned
        load(listOf(null), debounce = true)
    }

    fun refresh() = load(listOf(null))

    fun previousPage() {
        if (!loading && cursors.size > 1) load(cursors.dropLast(1))
    }

    fun nextPage() {
        val cursor = page.nextBeforeId ?: return
        if (!loading) load(cursors + cursor)
    }

    private fun load(targetCursors: List<Long?>, debounce: Boolean = false, keepCurrent: Boolean = false) {
        val sequence = ++querySequence
        queryJob?.cancel()
        queryInFlight = true
        queryIsAutomatic = keepCurrent
        loading = !keepCurrent
        error = null
        val requestedFilter = filter()
        if (targetCursors.size == 1 && !keepCurrent) page = RRLogPage(emptyList(), 0, null)
        queryJob = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                RRLogStore.state.first { it.ready }
                val readRevision = RRLogStore.state.value.revision
                val result = RRLogStore.query(requestedFilter, targetCursors.last(), 200)
                ensureActive()
                page = result
                cursors = targetCursors
                pageNumber = targetCursors.size
                lastReadRevision = readRevision
                hasLoaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "读取失败：${safeMessage(failure)}"
            } finally {
                if (querySequence == sequence) {
                    loading = false
                    queryInFlight = false
                }
            }
        }
    }

    fun clearHistory() {
        if (actionBusy || exportBusy) return
        actionBusy = true
        error = null
        viewModelScope.launch {
            try {
                RRLogStore.clearHistory()
                message = "历史记录已清空；采集开启时仍会产生新记录。"
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "清空失败：${safeMessage(failure)}"
            } finally { actionBusy = false }
        }
    }

    fun applyRetention(limit: Int) {
        if (actionBusy || exportBusy) return
        actionBusy = true
        error = null
        viewModelScope.launch {
            try {
                RRLogStore.setRetentionLimit(limit)
                message = "保留设置已保存：${retentionLabel(limit)}。"
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "设置失败：${safeMessage(failure)}"
            } finally { actionBusy = false }
        }
    }

    /** Persist the exact filter before launching the external document picker. */
    fun prepareExport(allLogs: Boolean): String? {
        if (exportBusy || awaitingDocument || actionBusy) return null
        val exportFilter = if (allLogs) RRLogFilter() else filter()
        saved["logExportChannel"] = exportFilter.channel
        saved["logExportSearch"] = exportFilter.search
        saved["logExportPending"] = true
        awaitingDocument = true
        error = null
        message = null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "RRBOX-${if (allLogs) "all-logs" else "filtered-logs"}-$stamp.txt"
    }

    fun exportLaunchFailed(failure: Exception) {
        clearPendingExport()
        error = "无法打开保存位置：${safeMessage(failure)}"
    }

    fun completeExport(uri: Uri?) {
        if (!awaitingDocument) return
        val exportFilter = RRLogFilter(saved["logExportChannel"], saved["logExportSearch"] ?: "")
        clearPendingExport()
        if (uri == null) {
            message = "已取消导出。"
            return
        }
        exportBusy = true
        message = "正在导出完整匹配记录…"
        exportJob = viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开文件写入流")
                    output.use { RRLogStore.export(it, exportFilter) }
                }
                message = "已导出 $count 条日志到 TXT 文件。"
            } catch (cancelled: CancellationException) {
                message = "导出已取消；未完成的文件不能作为完整日志使用。"
                throw cancelled
            } catch (failure: Exception) {
                error = "导出失败：${safeMessage(failure)}。文件可能未写完整，请重新导出。"
                message = null
            } finally {
                exportBusy = false
                exportJob = null
            }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    fun resetDocumentSelection() {
        clearPendingExport()
        message = "已重置导出，可重新选择保存位置。"
    }

    private fun clearPendingExport() {
        awaitingDocument = false
        saved["logExportPending"] = false
        saved.remove<String>("logExportChannel")
        saved.remove<String>("logExportSearch")
    }

    private fun safeMessage(failure: Exception) = RRLogStore.redact(failure.message ?: failure.javaClass.simpleName).take(250)
}

@Composable
internal fun LogCenterScreen(
    model: LogCenterViewModel,
    lightweight: Boolean,
    connectionLoggingActive: Boolean,
    onExport: (allLogs: Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(model, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            model.setVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        model.setVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            model.setVisible(false)
        }
    }
    val state by RRLogStore.state.collectAsState()
    var showRetention by rememberSaveable { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val locked = model.actionBusy || model.exportBusy || !state.ready
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("日志中心", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text(
            when {
                lightweight && connectionLoggingActive -> "轻量模式尚待应用，当前隧道仍在记录连接。请应用设置或重连。"
                lightweight -> "轻量模式：停止新增连接流向，仅采集警告和错误。已有历史仍可查看、搜索和导出。"
                connectionLoggingActive -> "正在记录经过 RRBOX 的连接与实际出口，离开此页后继续采集。"
                else -> "连接流向采集未启动。请关闭轻量模式并连接节点。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            "查看所有应用需选择「所有应用」接管范围；绕过 VPN 的流量不可见。HEV 可能无法识别原应用。记录已脱敏，不含请求正文。",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = model.routesOnly, onClick = { model.selectRoutesOnly(true) }, label = { Text("连接流向") })
            FilterChip(selected = !model.routesOnly, onClick = { model.selectRoutesOnly(false) }, label = { Text("全部日志") })
            FilterChip(
                selected = model.liveUpdates && model.pageNumber == 1 && model.search.isBlank(),
                onClick = model::toggleLiveUpdates,
                enabled = model.pageNumber == 1 && model.search.isBlank(),
                label = { Text("实时更新") }
            )
        }
        OutlinedTextField(
            value = model.search,
            onValueChange = model::updateSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索应用 / 包名 / 域名 / IP / 出口") }
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyLogPage(context, model.page.entries) }, enabled = !model.loading && model.page.entries.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, null)
                Text("复制本页")
            }
            Box {
                OutlinedButton(onClick = { showExportMenu = true }, enabled = !locked && !model.awaitingDocument && state.totalCount > 0) {
                    Icon(Icons.Default.SaveAlt, null)
                    Text("导出 TXT")
                }
                DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                    DropdownMenuItem(text = { Text("导出全部匹配记录") }, onClick = { showExportMenu = false; onExport(false) })
                    DropdownMenuItem(text = { Text("导出所有日志（不筛选）") }, onClick = { showExportMenu = false; onExport(true) })
                }
            }
            OutlinedButton(onClick = model::clearHistory, enabled = !locked && state.totalCount > 0) {
                Icon(Icons.Default.DeleteSweep, null)
                Text("清空")
            }
            OutlinedButton(onClick = { showRetention = true }, enabled = !locked) {
                Icon(Icons.Default.Storage, null)
                Text("保留条数")
            }
        }
        Text("已保留 ${state.totalCount} 条 · 上限 ${retentionLabel(state.retentionLimit)} · 匹配 ${model.page.filteredCount} 条", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text("每页 200 条；搜索覆盖全部历史，导出包含全部匹配记录。", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = model::previousPage, enabled = !model.loading && model.pageNumber > 1) { Text("上一页") }
            TextButton(onClick = model::refresh, enabled = !model.loading && state.ready) { Text("最新 · 刷新") }
            TextButton(onClick = model::nextPage, enabled = !model.loading && model.page.nextBeforeId != null) { Text("下一页") }
        }
        Text(
            "第 ${model.pageNumber} 页 · 最新在前 · " +
                if (model.liveUpdates && model.pageNumber == 1 && model.search.isBlank()) "每秒更新最新页" else "实时更新已暂停",
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        if (!model.loading && state.revision != model.lastReadRevision && model.lastReadRevision >= 0 &&
            (!model.liveUpdates || model.pageNumber > 1 || model.search.isNotBlank())) {
            Text("历史记录有变化，点「最新 · 刷新」重新读取。", color = CyanPrimary, style = MaterialTheme.typography.labelSmall)
        }
        if (model.loading || model.exportBusy || model.actionBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (model.exportBusy) TextButton(onClick = model::cancelExport) { Text("取消导出") }
        if (model.awaitingDocument) {
            Text("等待选择 TXT 保存位置…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::resetDocumentSelection) { Text("重置导出") }
        }
        model.message?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        (model.error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!model.loading && model.page.entries.isEmpty()) item { Text("暂无匹配记录。", color = TextSecondary) }
            items(model.page.entries, key = { it.id }) { entry ->
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = DarkSurface, border = BorderStroke(1.dp, CardBorder)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${timeFormat.format(Date(entry.timestamp))} · ${if (entry.channel == ConnectionRouteLog.CHANNEL) "连接流向" else entry.channel}",
                            color = CyanPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(entry.message, color = TextSecondary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
    if (showRetention) RetentionDialog(
        currentLimit = state.retentionLimit,
        totalCount = state.totalCount,
        busy = model.actionBusy,
        error = model.error,
        onDismiss = { if (!model.actionBusy) showRetention = false },
        onApply = { value -> showRetention = false; model.applyRetention(value) }
    )
}

@Composable
private fun RetentionDialog(currentLimit: Int, totalCount: Long, busy: Boolean, error: String?, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    val presets = listOf(600, 2000, 5000, 10000, 0)
    var selected by rememberSaveable { mutableIntStateOf(if (currentLimit in presets) currentLimit else -1) }
    var custom by rememberSaveable { mutableStateOf(if (currentLimit > 0) currentLimit.toString() else "20000") }
    val value = if (selected == -1) custom.toIntOrNull()?.takeIf { it in 100..1_000_000 } else selected
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日志保留条数") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("达到上限后自动删除最旧记录；设置重启后仍生效。", style = MaterialTheme.typography.bodySmall)
                listOf(listOf(600, 2000), listOf(5000, 10000), listOf(0, -1)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(
                                selected = selected == option,
                                onClick = { selected = option },
                                enabled = !busy,
                                label = { Text(when (option) { -1 -> "自定义"; 0 -> "不限条数"; 2000 -> "2000（默认）"; else -> option.toString() }) }
                            )
                        }
                    }
                }
                if (selected == -1) OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit).take(7) },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("100～1000000 条") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = value == null,
                    supportingText = { if (value == null) Text("请输入 100～1000000 之间的整数") }
                )
                if (value == 0) Text("不限条数会持续占用手机存储，直到手动清空。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (value != null && value > 0 && totalCount > value) Text("应用后仅保留最近 $value 条，较早记录将被删除且无法恢复；需要时请先导出。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onApply) }, enabled = value != null && !busy) { Text(if (busy) "保存中…" else "应用") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

private fun retentionLabel(limit: Int) = if (limit == 0) "不限条数" else "$limit 条"

private fun copyLogPage(context: Context, entries: List<RRStoredLogEntry>) {
    val text = buildString {
        appendLine("RRBOX 日志当前页（已脱敏，最新在前）")
        entries.forEach { appendLine("${it.timestamp}\t${it.channel}\t${it.message}") }
    }
    // Binder transactions must stay bounded even when all 200 rows contain long messages.
    if (text.length > 100_000) {
        Toast.makeText(context, "本页内容较长，请使用导出 TXT 保存完整记录。", Toast.LENGTH_LONG).show()
        return
    }
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RRBOX logs current page", text))
    }.onSuccess {
        Toast.makeText(context, "已复制本页 ${entries.size} 条记录", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "复制失败，请使用导出 TXT。", Toast.LENGTH_LONG).show()
    }
}

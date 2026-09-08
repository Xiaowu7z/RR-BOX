package com.rr.client.lab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary

@Composable
internal fun RootProbeCard(selectedEngine: String) {
    val context = LocalContext.current
    val state by RootProbeState.state.collectAsState()
    var showReport by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        RootProbeState.completeExport(context, uri)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Science, contentDescription = null, tint = CyanPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Root 隔离测试", color = TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "仅检查 Root 权限、临时 TUN 与清理；不会切换当前 System / HEV 或接管流量",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "由 RRBOX 请求 Root 授权。临时 TUN 保持关闭且不配置地址，不修改路由、iptables 或 DNS。",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            Button(
                onClick = { RootProbeState.start(context, selectedEngine) },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(if (state.busy) "Root 测试中…" else "Root 隔离测试", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CyanPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("等待授权及检查，最多 60 秒；离开页面后继续。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.report?.let { report ->
                val result = report.execution
                Text(
                    (if (state.busy) "上次报告：" else "") + result.outcome.title,
                    color = if (result.outcome == RootProbeOutcome.PASSED) CyanPrimary else TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                if (result.outcome == RootProbeOutcome.TIMED_OUT || result.outcome == RootProbeOutcome.INCOMPLETE) {
                    Text("未收到完整结果，不能确认本次隔离检查和清理通过；请查看报告。", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                result.launchError?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                RootProbeLine("Root 权限", result.values["root"])
                RootProbeLine("临时 TUN 创建", result.values["tun_create"])
                RootProbeLine("TUN 保持 down / 无地址", "${result.values["tun_down"] ?: "UNKNOWN"} / ${result.values["tun_unaddressed"] ?: "UNKNOWN"}")
                RootProbeLine("TUN 清理", result.values["tun_cleanup"])
                RootProbeLine("SELinux 上下文读取", result.values["selinux_context"])
                RootProbeLine("本进程 UID 0 socket 查询", "TCP ${result.values["sock_diag_tcp"] ?: "UNKNOWN"} / UDP ${result.values["sock_diag_udp"] ?: "UNKNOWN"}")
                RootProbeLine("IP_TRANSPARENT 能力", "IPv4 ${result.values["ip_transparent_v4"] ?: "UNKNOWN"} / IPv6 ${result.values["ip_transparent_v6"] ?: "UNKNOWN"}")
                Text(
                    "通过仅代表 Root 与临时 TUN 隔离检查通过。未验证公网、TLS/HTTPS、应用流量或应用 UID 映射。",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showReport = true }) { Text("查看报告") }
                    OutlinedButton(
                        enabled = !state.exportPending,
                        onClick = {
                            RootProbeState.prepareExport()?.let { name ->
                                try { export.launch(name) } catch (_: Exception) { RootProbeState.exportLaunchFailed() }
                            }
                        }
                    ) { Text(if (state.exportPending) "导出中…" else "导出报告") }
                }
            }
            state.exportMessage?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Root 隔离测试报告") },
            text = {
                SelectionContainer {
                    Text(
                        state.report?.toPlainText() ?: "尚无报告",
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showReport = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun RootProbeLine(label: String, status: String?) {
    Column {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Text(status ?: "UNKNOWN", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

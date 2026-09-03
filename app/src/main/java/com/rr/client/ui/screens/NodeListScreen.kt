package com.rr.client.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.friendlyLabel
import com.rr.client.security.NodeSecurityInspector
import com.rr.client.security.NodeSecurityRating
import com.rr.client.security.NodeSecurityReport
import com.rr.client.security.NodeSecuritySeverity
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.CyanSecondary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.DarkSurfaceVariant
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary

data class NodeGroupUi(
    val id: String,
    val name: String,
    val nodes: List<ProxyNode>,
    val isLocal: Boolean = false
)

@Composable
fun NodeListScreen(
    groups: List<NodeGroupUi>,
    selectedNodeId: String?,
    latencyStates: Map<String, NodeLatencyState>,
    editedNodeIds: Set<String>,
    onSelectNode: (ProxyNode) -> Unit,
    onPingAll: () -> Unit,
    onPingNode: (ProxyNode) -> Unit,
    onEditNode: (ProxyNode) -> Unit,
    onResetNodeEdit: (ProxyNode) -> Unit,
    onDeleteLocalNode: (ProxyNode) -> Unit,
    onImportText: (String) -> Unit,
    onCreateManualNode: (ProtocolType) -> Unit,
    onGoToSubscription: () -> Unit
) {
    val context = LocalContext.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var showImportMethods by remember { mutableStateOf(false) }
    var showTextImport by remember { mutableStateOf(false) }
    var showManualProtocols by remember { mutableStateOf(false) }

    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.takeIf(String::isNotEmpty)?.let(onImportText)
    }

    LaunchedEffect(groups.map { it.id }, selectedNodeId) {
        groups.forEachIndexed { index, group ->
            if (!expanded.containsKey(group.id)) {
                expanded[group.id] = group.nodes.any { it.id == selectedNodeId } ||
                    group.isLocal || (selectedNodeId == null && index == 0)
            }
        }
    }

    val totalNodes = groups.sumOf { it.nodes.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "节点列表 ($totalNodes)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "订阅分组与本地节点独立管理",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (totalNodes > 0) {
                TextButton(onClick = onPingAll) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = CyanPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("测速", color = CyanPrimary)
                }
            }
            IconButton(onClick = { showImportMethods = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加节点", tint = CyanPrimary)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "暂无节点", color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showImportMethods = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text("添加本地节点", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onGoToSubscription) { Text("去添加订阅") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    item(key = "group-${group.id}") {
                        GroupHeader(
                            group = group,
                            expanded = expanded[group.id] == true,
                            onToggle = { expanded[group.id] = !(expanded[group.id] ?: false) }
                        )
                    }
                    if (expanded[group.id] == true) {
                        items(group.nodes, key = { it.id }) { node ->
                            NodeCard(
                                node = node,
                                isSelected = node.id == selectedNodeId,
                                latencyState = latencyStates[node.id] ?: NodeLatencyState.Idle,
                                isEdited = node.id in editedNodeIds,
                                isLocal = group.isLocal,
                                onSelectNode = { onSelectNode(node) },
                                onPingNode = { onPingNode(node) },
                                onEditNode = { onEditNode(node) },
                                onResetNodeEdit = { onResetNodeEdit(node) },
                                onDeleteLocalNode = { onDeleteLocalNode(node) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportMethods) {
        NodeImportMethodDialog(
            onDismiss = { showImportMethods = false },
            onQr = {
                showImportMethods = false
                qrLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("扫描代理节点二维码")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            },
            onClipboard = {
                showImportMethods = false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                onImportText(text)
            },
            onText = {
                showImportMethods = false
                showTextImport = true
            },
            onManual = {
                showImportMethods = false
                showManualProtocols = true
            }
        )
    }

    if (showTextImport) {
        NodeTextImportDialog(
            onDismiss = { showTextImport = false },
            onImport = {
                showTextImport = false
                onImportText(it)
            }
        )
    }

    if (showManualProtocols) {
        ManualProtocolDialog(
            onDismiss = { showManualProtocols = false },
            onSelect = {
                showManualProtocols = false
                onCreateManualNode(it)
            }
        )
    }
}

@Composable
private fun GroupHeader(group: NodeGroupUi, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (group.isLocal) CyanSecondary else CyanPrimary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        color = accent.copy(alpha = if (expanded) 0.18f else 0.12f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.68f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (group.isLocal) "本机单独添加 · ${group.nodes.size} 个" else "订阅节点 · ${group.nodes.size} 个",
                    color = accent.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkBackground.copy(alpha = 0.72f)
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = accent,
                    modifier = Modifier.padding(3.dp)
                )
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: ProxyNode,
    isSelected: Boolean,
    latencyState: NodeLatencyState,
    isEdited: Boolean,
    isLocal: Boolean,
    onSelectNode: () -> Unit,
    onPingNode: () -> Unit,
    onEditNode: () -> Unit,
    onResetNodeEdit: () -> Unit,
    onDeleteLocalNode: () -> Unit
) {
    var menuExpanded by remember(node.id) { mutableStateOf(false) }
    var securityReport by remember(node.id) { mutableStateOf<NodeSecurityReport?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectNode),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface),
        border = BorderStroke(1.dp, if (isSelected) CyanPrimary else CardBorder)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.tag,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${maskNodeAddress(node.server)}:${node.serverPort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(node)
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(7.dp))
                        Icon(Icons.Default.Check, contentDescription = "已选择", tint = CyanPrimary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LatencyBadge(latencyState)
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "节点操作", tint = TextSecondary)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("测试 Ping") },
                        leadingIcon = { Icon(Icons.Default.Speed, null) },
                        onClick = { menuExpanded = false; onPingNode() }
                    )
                    DropdownMenuItem(
                        text = { Text("安全检查") },
                        leadingIcon = { Icon(Icons.Default.Security, null) },
                        onClick = {
                            menuExpanded = false
                            securityReport = NodeSecurityInspector.inspect(node)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("编辑节点") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuExpanded = false; onEditNode() }
                    )
                    if (!isLocal && isEdited) {
                        DropdownMenuItem(
                            text = { Text("恢复订阅值") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { menuExpanded = false; onResetNodeEdit() }
                        )
                    }
                    if (isLocal) {
                        DropdownMenuItem(
                            text = { Text("删除节点") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuExpanded = false; onDeleteLocalNode() }
                        )
                    }
                }
            }
        }
    }

    securityReport?.let { report ->
        NodeSecurityDialog(
            node = node,
            report = report,
            onDismiss = { securityReport = null }
        )
    }
}

@Composable
private fun NodeSecurityDialog(
    node: ProxyNode,
    report: NodeSecurityReport,
    onDismiss: () -> Unit
) {
    val ratingColor = when (report.rating) {
        NodeSecurityRating.GOOD -> CyanPrimary
        NodeSecurityRating.ATTENTION -> MaterialTheme.colorScheme.tertiary
        NodeSecurityRating.HIGH_RISK -> MaterialTheme.colorScheme.error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("节点安全检查")
                Text(
                    node.tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ratingColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ratingColor.copy(alpha = 0.48f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(report.title, color = ratingColor, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text(report.summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                report.findings.forEach { finding ->
                    val findingColor = when (finding.severity) {
                        NodeSecuritySeverity.PASS -> CyanPrimary
                        NodeSecuritySeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        NodeSecuritySeverity.DANGER -> MaterialTheme.colorScheme.error
                    }
                    val marker = when (finding.severity) {
                        NodeSecuritySeverity.PASS -> "✓"
                        NodeSecuritySeverity.WARNING -> "!"
                        NodeSecuritySeverity.DANGER -> "×"
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(marker, color = findingColor, fontWeight = FontWeight.Bold)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                finding.title,
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                finding.detail,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text(
                    "说明：这里只检查节点配置、协议和 TLS 暴露面；不能证明节点运营者可信，也不判断出口 IP 信誉或服务端是否记录流量。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun NodeImportMethodDialog(
    onDismiss: () -> Unit,
    onQr: () -> Unit,
    onClipboard: () -> Unit,
    onText: () -> Unit,
    onManual: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加本地节点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onQr, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("扫描二维码")
                }
                OutlinedButton(onClick = onClipboard, modifier = Modifier.fillMaxWidth()) { Text("从剪贴板导入") }
                OutlinedButton(onClick = onText, modifier = Modifier.fillMaxWidth()) { Text("粘贴 / 输入链接或 JSON") }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("手动配置节点") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NodeTextImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入节点") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                label = { Text("分享链接 / Base64 / sing-box JSON / Clash YAML") }
            )
        },
        confirmButton = { Button(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManualProtocolDialog(onDismiss: () -> Unit, onSelect: (ProtocolType) -> Unit) {
    val options = listOf(
        ProtocolType.VLESS_REALITY,
        ProtocolType.VLESS_TLS,
        ProtocolType.VMESS_WS_ARGO,
        ProtocolType.VMESS_TLS,
        ProtocolType.HYSTERIA2,
        ProtocolType.TUIC_V5,
        ProtocolType.TROJAN,
        ProtocolType.SHADOWSOCKS
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择协议") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { protocol ->
                    OutlinedButton(onClick = { onSelect(protocol) }, modifier = Modifier.fillMaxWidth()) {
                        Text(protocol.friendlyLabel())
                    }
                }
                Text(
                    "AnyTLS 已支持订阅、扫码和链接导入；Naive、SOCKS/HTTP/SSH 等也可通过链接或 sing-box JSON 导入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun maskNodeAddress(value: String): String {
    val host = value.trim()
    val ipv4 = host.split('.')
    if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }) {
        return "${ipv4[0]}.***.***.${ipv4[3]}"
    }
    if (host.contains(':')) {
        val parts = host.removePrefix("[").removeSuffix("]").split(':')
        return "[${parts.firstOrNull().orEmpty()}:****:****:${parts.lastOrNull().orEmpty()}]"
    }
    if (host.length > 5) return "${host.take(2)}***${host.takeLast(2)}"
    return "***"
}

@Composable
private fun ProtocolBadge(node: ProxyNode) {
    Surface(shape = RoundedCornerShape(6.dp), color = DarkBackground) {
        Text(
            text = node.type.friendlyLabel(),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = CyanSecondary
        )
    }
}

@Composable
private fun LatencyBadge(state: NodeLatencyState) {
    Surface(shape = RoundedCornerShape(6.dp), color = DarkBackground) {
        Text(
            text = when (state) {
                NodeLatencyState.Idle -> "Ping --"
                NodeLatencyState.Testing -> "测试中…"
                is NodeLatencyState.Success -> "${state.millis} ms"
                NodeLatencyState.Timeout -> "超时"
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = when (state) {
                is NodeLatencyState.Success -> CyanPrimary
                NodeLatencyState.Testing -> CyanSecondary
                else -> TextSecondary
            }
        )
    }
}

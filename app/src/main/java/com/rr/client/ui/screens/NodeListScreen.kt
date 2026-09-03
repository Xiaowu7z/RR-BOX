package com.rr.client.ui.screens

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.friendlyLabel
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.CyanSecondary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.DarkSurfaceVariant
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary

@Composable
fun NodeListScreen(
    nodes: List<ProxyNode>,
    selectedNodeId: String?,
    latencyStates: Map<String, NodeLatencyState>,
    editedNodeIds: Set<String>,
    onSelectNode: (ProxyNode) -> Unit,
    onPingAll: () -> Unit,
    onPingNode: (ProxyNode) -> Unit,
    onEditNode: (ProxyNode) -> Unit,
    onResetNodeEdit: (ProxyNode) -> Unit,
    onGoToSubscription: () -> Unit
) {
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
            Text(
                text = "节点列表 (${nodes.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (nodes.isNotEmpty()) {
                TextButton(onClick = onPingAll) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = CyanPrimary)
                    Spacer(Modifier.width(4.dp))
                    Text("测速", color = CyanPrimary)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (nodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "暂无节点", color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onGoToSubscription,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("去添加订阅", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodes, key = { it.id }) { node ->
                    val isSelected = node.id == selectedNodeId
                    val isEdited = node.id in editedNodeIds
                    var menuExpanded by remember(node.id) { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectNode(node) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
                        ),
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
                                    text = buildString {
                                        if (node.profileName.isNotBlank()) {
                                            append(node.profileName)
                                            append(" · ")
                                        }
                                        append(maskNodeAddress(node.server))
                                        append(":")
                                        append(node.serverPort)
                                    },
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
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "已选择",
                                            tint = CyanPrimary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                LatencyBadge(latencyStates[node.id] ?: NodeLatencyState.Idle)
                            }

                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "节点操作",
                                        tint = TextSecondary
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("测试 Ping") },
                                        leadingIcon = { Icon(Icons.Default.Speed, null) },
                                        onClick = {
                                            menuExpanded = false
                                            onPingNode(node)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("编辑节点") },
                                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                                        onClick = {
                                            menuExpanded = false
                                            onEditNode(node)
                                        }
                                    )
                                    if (isEdited) {
                                        DropdownMenuItem(
                                            text = { Text("恢复订阅值") },
                                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                            onClick = {
                                                menuExpanded = false
                                                onResetNodeEdit(node)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun maskNodeAddress(value: String): String {
    val host = value.trim()
    val ipv4 = host.split('.')
    if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }) {
        return "${ipv4[0]}.***.***.${ipv4[3]}"
    }

    if (host.contains(':')) {
        val parts = host.removePrefix("[").removeSuffix("]").split(':')
        val first = parts.firstOrNull().orEmpty()
        val last = parts.lastOrNull().orEmpty()
        return "[$first:****:****:$last]"
    }

    return host
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

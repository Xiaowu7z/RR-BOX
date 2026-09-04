package com.rr.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rr.client.subscription.model.SubProfile
import com.rr.client.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SubscriptionScreen(
    profiles: List<SubProfile>,
    busyIds: Set<String>,
    adding: Boolean,
    onAddProfile: (name: String, url: String) -> Unit,
    onRefreshProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var showAddForm by remember { mutableStateOf(profiles.isEmpty()) }
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<SubProfile?>(null) }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = DarkSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("删除订阅「${candidate.name}」？") },
            text = { Text("将同时移除该订阅下的 ${candidate.nodes.size} 个节点，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    onDeleteProfile(candidate.id)
                }) { Text("删除", color = AccentRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "订阅管理（${profiles.size} 组）",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "HTTP/HTTPS、IPv4、[IPv6]、带端口及省略协议头的订阅地址均可尝试；不同订阅组会在节点页分组显示。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (!showAddForm) {
            OutlinedButton(
                onClick = {
                    nameInput = ""
                    urlInput = ""
                    showAddForm = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, CyanPrimary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = CyanPrimary)
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加订阅", color = CyanPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showAddForm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "新订阅",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("订阅名称（可选）", color = TextSecondary) },
                        placeholder = { Text("例如：RRVPS 主订阅", color = TextSecondary.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("订阅地址", color = TextSecondary) },
                        placeholder = { Text("1.2.3.4:8080/sub 或 https://example.com/sub", color = TextSecondary.copy(alpha = 0.6f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = textFieldColors()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "省略 http:// 或 https:// 时，RRBOX 会先尝试 HTTPS，再尝试 HTTP。HTTP 订阅本身不加密，只建议用于你信任的自建面板。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                nameInput = ""
                                urlInput = ""
                                showAddForm = false
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("取消", color = TextSecondary) }
                        Button(
                            onClick = {
                                val submittedName = nameInput.trim()
                                val submittedUrl = urlInput.trim()
                                nameInput = ""
                                urlInput = ""
                                showAddForm = false
                                onAddProfile(submittedName, submittedUrl)
                            },
                            enabled = !adding && urlInput.isNotBlank(),
                            modifier = Modifier.weight(2f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            if (adding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = DarkBackground
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (adding) "同步中…" else "添加并同步", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (profiles.isEmpty() && !showAddForm) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有订阅组\n点击上方「添加订阅」输入订阅地址",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (profiles.isNotEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    SubscriptionCard(
                        profile = profile,
                        busy = profile.id in busyIds,
                        onRefresh = { onRefreshProfile(profile.id) },
                        onDelete = { deleteCandidate = profile }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    profile: SubProfile,
    busy: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        val ui = profile.userInfo
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = profile.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(shape = RoundedCornerShape(6.dp), color = DarkBackground) {
                    Text(
                        text = "${profile.nodes.size} 节点",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (profile.lastUpdated > 0L)
                        "更新于 ${dateFmt.format(Date(profile.lastUpdated))}"
                    else "尚未同步",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (ui.total > 0L) {
                    Text(
                        text = "已用 ${formatGb(ui.usedBytes)} / ${formatGb(ui.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            if (ui.total > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { ui.usagePercentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CyanPrimary,
                    trackColor = DarkSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = CyanPrimary)
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = CyanPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("同步中", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("更新", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = DarkBackground,
    unfocusedContainerColor = DarkBackground,
    focusedBorderColor = CyanPrimary,
    unfocusedBorderColor = CardBorder,
    focusedLabelColor = CyanPrimary,
    unfocusedLabelColor = TextSecondary,
    cursorColor = CyanPrimary,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)

private fun formatGb(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
    val mb = bytes / 1024.0 / 1024.0
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

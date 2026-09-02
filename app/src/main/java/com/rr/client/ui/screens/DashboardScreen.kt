package com.rr.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.model.SubscriptionUserInfo
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSampler
import com.rr.client.traffic.TrafficSpeed
import com.rr.client.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    isConnected: Boolean,
    isStarting: Boolean,
    currentSpeed: TrafficSpeed,
    sessionTraffic: SessionTraffic,
    userInfo: SubscriptionUserInfo?,
    selectedNode: ProxyNode?,
    onToggleVpn: () -> Unit,
    onNavigateToNodes: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToNodes() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "当前选定节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedNode?.tag ?: "未选择节点",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceVariant
                ) {
                    Text(
                        text = selectedNode?.type?.name ?: "NONE",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(if (isConnected) CyanPrimary.copy(alpha = 0.15f) else DarkSurfaceVariant)
                .clickable(enabled = !isStarting) { onToggleVpn() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) CyanPrimary else DarkSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "VPN Switch",
                    modifier = Modifier.size(56.dp),
                    tint = when {
                        isConnected -> DarkBackground
                        isStarting -> CyanPrimary
                        else -> TextSecondary
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when {
                isConnected -> "已连接 · 保护中"
                isStarting -> "正在连接 · 请稍候"
                selectedNode == null -> "请先导入订阅"
                else -> "未连接 · 点击开启"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                isConnected -> AccentGreen
                isStarting -> CyanPrimary
                else -> TextSecondary
            }
        )

        if (isStarting) {
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CyanPrimary,
                trackColor = DarkSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download",
                        tint = CyanPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "实时下载", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            text = currentSpeed.formattedDownSpeed,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp),
                    color = CardBorder
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload",
                        tint = AccentGreen,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "实时上传", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            text = currentSpeed.formattedUpSpeed,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "本次连接累计",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "代理下载: ${TrafficSampler.formatBytes(sessionTraffic.proxyDownloadTotal)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "代理上传: ${TrafficSampler.formatBytes(sessionTraffic.proxyUploadTotal)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                if (userInfo != null && userInfo.total > 0L) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "RRVPS 订阅额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { userInfo.usagePercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = CyanPrimary,
                        trackColor = DarkSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "已用: ${TrafficSampler.formatBytes(userInfo.usedBytes)} / ${TrafficSampler.formatBytes(userInfo.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        val expireStr = if (userInfo.expireTimestamp > 0L) {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(
                                Date(userInfo.expireTimestamp * 1000L)
                            )
                        } else {
                            "长期有效"
                        }
                        Text(
                            text = "到期: $expireStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

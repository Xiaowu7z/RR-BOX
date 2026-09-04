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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.friendlyLabel
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
    currentSpeed: TrafficSpeed,
    sessionTraffic: SessionTraffic,
    userInfo: SubscriptionUserInfo?,
    profileName: String?,
    selectedNode: ProxyNode?,
    onToggleVpn: () -> Unit,
    onNavigateToNodes: () -> Unit
) {
    var peakDown by remember { mutableLongStateOf(0L) }
    var peakUp by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            peakDown = 0L
            peakUp = 0L
        }
    }
    LaunchedEffect(currentSpeed.downloadBytesPerSec, currentSpeed.uploadBytesPerSec, isConnected) {
        if (isConnected) {
            peakDown = maxOf(peakDown, currentSpeed.downloadBytesPerSec.coerceAtLeast(0L))
            peakUp = maxOf(peakUp, currentSpeed.uploadBytesPerSec.coerceAtLeast(0L))
        }
    }

    val avgDown = if (sessionTraffic.durationSeconds > 0L) {
        sessionTraffic.proxyDownloadTotal / sessionTraffic.durationSeconds
    } else 0L
    val avgUp = if (sessionTraffic.durationSeconds > 0L) {
        sessionTraffic.proxyUploadTotal / sessionTraffic.durationSeconds
    } else 0L

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
                    Text(text = "当前选定节点", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = selectedNode?.tag ?: "未选择节点",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = DarkSurfaceVariant) {
                    Text(
                        text = selectedNode?.type?.friendlyLabel() ?: "未导入",
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
                .clickable { onToggleVpn() },
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
                    tint = if (isConnected) DarkBackground else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isConnected) "已连接 · 保护中" else "未连接 · 点击开启",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isConnected) AccentGreen else TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowDownward, "Download", tint = CyanPrimary, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "实时下载", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(currentSpeed.formattedDownSpeed, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                }

                Divider(modifier = Modifier.height(36.dp).width(1.dp), color = CardBorder)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowUpward, "Upload", tint = AccentGreen, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = "实时上传", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(currentSpeed.formattedUpSpeed, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                Text(text = "本次连接累计", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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

                Spacer(modifier = Modifier.height(10.dp))
                Divider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))
                Text("实时流量中心", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("时长: ${formatSessionDuration(sessionTraffic.durationSeconds)}", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("平均 ↓ ${TrafficSampler.formatSpeed(avgDown)}", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Text("平均 ↑ ${TrafficSampler.formatSpeed(avgUp)}", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("峰值下载: ${TrafficSampler.formatSpeed(peakDown)}", style = MaterialTheme.typography.bodySmall, color = CyanPrimary)
                    Text("峰值上传: ${TrafficSampler.formatSpeed(peakUp)}", style = MaterialTheme.typography.bodySmall, color = AccentGreen)
                }

                if (userInfo != null && userInfo.total > 0L) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = CardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (!profileName.isNullOrBlank()) "订阅额度 · $profileName" else "订阅额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { userInfo.usagePercentage },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = CyanPrimary,
                        trackColor = DarkSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "已用: ${TrafficSampler.formatBytes(userInfo.usedBytes)} / ${TrafficSampler.formatBytes(userInfo.total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        val expireStr = if (userInfo.expireTimestamp > 0L) {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(userInfo.expireTimestamp * 1000L))
                        } else "长期有效"
                        Text(text = "到期: $expireStr", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        }
    }
}

private fun formatSessionDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

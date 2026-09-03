package com.rr.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.BuildConfig
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    smartRouting: Boolean,
    backgroundProtected: Boolean,
    onSmartRoutingChanged: (Boolean) -> Unit,
    onRequestBackgroundProtection: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "RRBOX 设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "基础智能分流", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            text = "开启后直连局域网私有地址和 .cn 域名；关闭后其余流量统一交给当前代理节点。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = smartRouting,
                        onCheckedChange = onSmartRoutingChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBackground,
                            checkedTrackColor = CyanPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "说明：当前版本没有内置完整中国大陆 IP/域名规则集，因此这里不宣称全量 CN 分流。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, if (backgroundProtected) CyanPrimary else CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "后台运行保护", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (backgroundProtected) {
                        "已允许 RRBOX 不受 Android 电池优化限制，适合长期保持 VPN。"
                    } else {
                        "当前可能受系统电池优化影响。建议允许 RRBOX 不受电池优化限制，减少息屏或后台时被系统停止。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (!backgroundProtected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onRequestBackgroundProtection,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        Text("去授权", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "关于 RRBOX", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "版本: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(text = "sing-box 内核: v1.14.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(text = "运行方式: Android 标准 VpnService", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(text = "最低系统: Android 8.0 (API 26)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Text(text = "当前构建架构: arm64-v8a", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

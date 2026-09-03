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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    smartRouting: Boolean,
    backgroundProtected: Boolean,
    ruleSetLastUpdated: Long,
    ruleSetUpdating: Boolean,
    pinEnabled: Boolean,
    onSmartRoutingChanged: (Boolean) -> Unit,
    onRequestBackgroundProtection: () -> Unit,
    onUpdateRuleSets: () -> Unit,
    onEnablePin: () -> Unit,
    onDisablePin: () -> Unit,
    onChangePin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "RRBOX 设置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))

        SettingsCard(borderHighlighted = smartRouting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "中国大陆智能分流", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = "使用 SagerNet 二进制规则集识别中国大陆域名与 IP；局域网也保持直连。",
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
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (ruleSetLastUpdated > 0L) {
                    "本地规则更新时间：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ruleSetLastUpdated))}"
                } else {
                    "使用 APK 内置规则快照；首次运行会初始化。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onUpdateRuleSets,
                enabled = !ruleSetUpdating,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (ruleSetUpdating) "正在更新…" else "立即更新中国规则",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(borderHighlighted = backgroundProtected) {
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

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(borderHighlighted = pinEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "软件 PIN 锁", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = if (pinEnabled) {
                            "已开启。RRBOX 界面重新进入前台时需要 PIN；已运行的 VPN 不会被锁屏停止。"
                        } else {
                            "可选的 4-8 位数字 PIN。凭据使用 PBKDF2 加盐校验，不保存明文 PIN。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = pinEnabled,
                    onCheckedChange = { enabled -> if (enabled) onEnablePin() else onDisablePin() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBackground,
                        checkedTrackColor = CyanPrimary
                    )
                )
            }
            if (pinEnabled) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onChangePin,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    Text("修改 PIN", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "忘记 PIN 时只能清除 RRBOX 应用数据重新配置。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Text(text = "关于 RRBOX", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "版本: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "sing-box 内核: v1.14.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "运行方式: Android 标准 VpnService", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "最低系统: Android 8.0 (API 26)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "当前构建架构: arm64-v8a", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(
    borderHighlighted: Boolean = false,
    content: @Composable Column.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, if (borderHighlighted) CyanPrimary else CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

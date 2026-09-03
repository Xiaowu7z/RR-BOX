package com.rr.client.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rr.client.BuildConfig
import com.rr.client.RRApplication
import com.rr.client.storage.PreferencesManager
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary
import com.rr.client.vpn.HevTunnelConfig
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    smartRouting: Boolean,
    fastForwarding: Boolean,
    backgroundProtected: Boolean,
    ruleSetLastUpdated: Long,
    ruleSetUpdating: Boolean,
    pinEnabled: Boolean,
    pinMaxFailedAttempts: Int,
    checkingAppUpdate: Boolean,
    onSmartRoutingChanged: (Boolean) -> Unit,
    onFastForwardingChanged: (Boolean) -> Unit,
    onRequestBackgroundProtection: () -> Unit,
    onUpdateRuleSets: () -> Unit,
    onEnablePin: () -> Unit,
    onDisablePin: () -> Unit,
    onChangePin: () -> Unit,
    onPinMaxFailedAttemptsChanged: (Int) -> Unit,
    onCheckAppUpdate: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = RRApplication.instance.preferencesManager
    val tunEngine by preferences.tunEngine.collectAsState(initial = PreferencesManager.TUN_ENGINE_SYSTEM)

    fun switchTunEngine(engine: String) {
        if (engine == tunEngine) return
        scope.launch {
            preferences.setTunEngine(engine)
            if (RRVpnService.isRunning.value || RRVpnService.isStarting.value) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RRVpnService::class.java).apply {
                        action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
                    }
                )
            }
        }
    }

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
                        text = "使用维护中的 SagerNet 中国域名与 IP 二进制规则集；局域网地址保持直连。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = smartRouting,
                    onCheckedChange = onSmartRoutingChanged,
                    colors = SwitchDefaults.colors(checkedThumbColor = DarkBackground, checkedTrackColor = CyanPrimary)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (ruleSetLastUpdated > 0L) {
                    "本地规则更新时间：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ruleSetLastUpdated))}"
                } else {
                    "使用 APK 内置规则快照；可手动更新。"
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

        SettingsCard(borderHighlighted = fastForwarding) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "轻量模式", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(
                        text = "不改变 TUN 引擎与节点协议；降低运行日志，并在关闭智能分流时跳过全局流量嗅探，减少额外 CPU 开销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = fastForwarding,
                    onCheckedChange = onFastForwardingChanged,
                    colors = SwitchDefaults.colors(checkedThumbColor = DarkBackground, checkedTrackColor = CyanPrimary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "它是低开销模式，不等同于下面的 HEV 底层极速引擎；两者可以同时开启。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(borderHighlighted = tunEngine == PreferencesManager.TUN_ENGINE_HEV) {
            Text(text = "转发引擎", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (tunEngine == PreferencesManager.TUN_ENGINE_HEV) {
                    "当前：HEV 极速引擎。Android TUN 交给 native C/lwIP，再通过本机 SOCKS5 进入 sing-box。"
                } else {
                    "当前：稳定引擎。使用已实机验证的 sing-box system TUN 数据面。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (tunEngine == PreferencesManager.TUN_ENGINE_SYSTEM) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) { Text("稳定模式", color = DarkBackground, fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = { switchTunEngine(PreferencesManager.TUN_ENGINE_SYSTEM) },
                        modifier = Modifier.weight(1f)
                    ) { Text("稳定模式") }
                }

                if (tunEngine == PreferencesManager.TUN_ENGINE_HEV) {
                    Button(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) { Text("HEV 极速", color = DarkBackground, fontWeight = FontWeight.Bold) }
                } else {
                    OutlinedButton(
                        onClick = { switchTunEngine(PreferencesManager.TUN_ENGINE_HEV) },
                        modifier = Modifier.weight(1f)
                    ) { Text("HEV 极速") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "HEV 模式使用 ${HevTunnelConfig.MTU} MTU、mapped DNS 与加大的 native 缓冲区来降低 TUN 数据面的包处理开销。属于实验引擎；切换会自动重建当前 VPN，稳定模式始终保留。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(borderHighlighted = backgroundProtected) {
            Text(text = "后台运行保护", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (backgroundProtected) {
                    "已允许 RRBOX 不受 Android 电池优化限制。"
                } else {
                    "建议允许 RRBOX 不受电池优化限制，减少息屏或后台时被系统停止。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onRequestBackgroundProtection,
                enabled = !backgroundProtected,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (backgroundProtected) "已授权" else "去授权",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
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
                            "重新进入 RRBOX 前先显示锁屏，不会先闪出内部界面。"
                        } else {
                            "可选 4-8 位数字 PIN；只保存 PBKDF2 加盐校验值。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = pinEnabled,
                    onCheckedChange = { enabled -> if (enabled) onEnablePin() else onDisablePin() },
                    colors = SwitchDefaults.colors(checkedThumbColor = DarkBackground, checkedTrackColor = CyanPrimary)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("错误次数保护", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        "连续输错达到设定次数后，自动清除 RRBOX 自身内部数据并恢复首次安装状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = pinMaxFailedAttempts > PreferencesManager.MIN_PIN_MAX_FAILED_ATTEMPTS,
                        onClick = { onPinMaxFailedAttemptsChanged(pinMaxFailedAttempts - 1) }
                    ) { Text("−") }
                    Text("$pinMaxFailedAttempts 次", color = CyanPrimary, fontWeight = FontWeight.Bold)
                    TextButton(
                        enabled = pinMaxFailedAttempts < PreferencesManager.MAX_PIN_MAX_FAILED_ATTEMPTS,
                        onClick = { onPinMaxFailedAttemptsChanged(pinMaxFailedAttempts + 1) }
                    ) { Text("+") }
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = if (pinEnabled) onChangePin else onEnablePin,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (pinEnabled) "修改 PIN" else "设置 PIN",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Text(text = "软件更新", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text("通过 GitHub Releases 检查正式版本。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCheckAppUpdate,
                enabled = !checkingAppUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (checkingAppUpdate) "正在检查…" else "检查软件更新",
                    color = DarkBackground,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard {
            Text(text = "关于 RRBOX", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "版本: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "sing-box 内核: v1.14.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "转发引擎: system TUN / HEV native", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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
    content: @Composable ColumnScope.() -> Unit
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

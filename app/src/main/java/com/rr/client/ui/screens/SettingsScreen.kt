package com.rr.client.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rr.client.BuildConfig
import com.rr.client.RRApplication
import com.rr.client.lab.NetworkLabActivity
import com.rr.client.storage.PreferencesManager
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onCheckAppUpdate: () -> Unit,
    ruleVersion: Long = 0L,
    ruleBundleVersion: Long = 0L,
    ruleDescription: String = "",
    ruleUpdateMessage: String = "",
    onVpnPermissionPendingChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = RRApplication.instance.preferencesManager
    val tunEngine by preferences.tunEngine.collectAsState(initial = PreferencesManager.TUN_ENGINE_SYSTEM)
    var pendingTunEngine by rememberSaveable { mutableStateOf<String?>(null) }
    var engineSwitchBusy by remember { mutableStateOf(false) }

    fun commitTunEngine(engine: String) {
        engineSwitchBusy = true
        scope.launch {
            try {
                // Once saved, finish dispatching the corresponding restart even if the
                // settings tab leaves composition while DataStore commits the selection.
                withContext(NonCancellable) {
                    val previousEngine = preferences.tunEngine.first()
                    preferences.setTunEngine(engine)
                    com.rr.client.lab.AppRoutingDiagnostics.record(
                        "引擎偏好已保存；原选择=$previousEngine；新选择=$engine；尚待运行结果")
                    try {
                        if (RRVpnService.isRunning.value || RRVpnService.isStarting.value) {
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, RRVpnService::class.java).apply {
                                    action = RRVpnService.ACTION_RESTART_ACTIVE_ENGINE
                                }
                            )
                            com.rr.client.lab.AppRoutingDiagnostics.record(
                                "引擎切换请求已交给服务；目标=$engine；原运行代次=${RRVpnService.currentRuntimeGeneration()}")
                        } else {
                            com.rr.client.lab.AppRoutingDiagnostics.record("引擎偏好等待下次连接；目标=$engine")
                        }
                    } catch (error: Exception) {
                        preferences.setTunEngine(previousEngine)
                        com.rr.client.lab.AppRoutingDiagnostics.record(
                            "引擎切换请求失败，偏好已恢复；恢复为=$previousEngine；失败目标=$engine；原因=${error.message.orEmpty().take(1000)}")
                        throw error
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                com.rr.client.lab.AppRoutingDiagnostics.record(
                    "引擎选择失败；目标=$engine；异常=${error.javaClass.simpleName}；原因=${error.message.orEmpty().take(1000)}")
                Toast.makeText(context, "切换引擎失败：${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally {
                engineSwitchBusy = false
            }
        }
    }

    val enginePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onVpnPermissionPendingChanged(false)
        val requestedEngine = pendingTunEngine
        pendingTunEngine = null
        if (requestedEngine != null && result.resultCode == Activity.RESULT_OK) {
            commitTunEngine(requestedEngine)
        } else if (requestedEngine != null) {
            com.rr.client.lab.AppRoutingDiagnostics.record(
                "引擎切换未执行；目标=$requestedEngine；VPN 授权未通过，保留原设置")
            Toast.makeText(context, "VPN 授权未通过，保留当前引擎", Toast.LENGTH_LONG).show()
        }
    }

    fun switchTunEngine(engine: String) {
        if (engine == tunEngine || engineSwitchBusy || pendingTunEngine != null) return
        com.rr.client.lab.AppRoutingDiagnostics.record(
            "用户选择引擎；原选择=$tunEngine；目标=$engine；当前运行引擎=${RRVpnService.activeRuntimeEngine.value ?: "未连接"}")
        // Ask only for an explicit System/HEV target. Selecting Root must never prepare a VPN.
        if (engine != PreferencesManager.TUN_ENGINE_ROOT) {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent != null) {
                pendingTunEngine = engine
                try {
                    onVpnPermissionPendingChanged(true)
                    enginePermissionLauncher.launch(permissionIntent)
                } catch (error: Exception) {
                    onVpnPermissionPendingChanged(false)
                    pendingTunEngine = null
                    com.rr.client.lab.AppRoutingDiagnostics.record(
                        "引擎切换授权界面打开失败；目标=$engine；原因=${error.message.orEmpty().take(1000)}")
                    Toast.makeText(context, "无法打开 VPN 授权：${error.message}", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        commitTunEngine(engine)
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
                        text = "国内服务与局域网直连，海外服务走当前节点。微信、国内抖音与海外 TikTok 分开判断。",
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
                text = if (smartRouting) {
                    "已开启日常分流：微信消息、媒体与视频号，国内抖音视频与直播使用专项域名规则；TikTok（含改包名的第三方版本）按海外服务域名走代理。"
                } else {
                    "已关闭智能分流：纳入接管范围的业务连接全部走当前代理节点。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (ruleSetLastUpdated > 0L) {
                    "上次保存时间：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ruleSetLastUpdated))}"
                } else {
                    "使用 APK 内置规则快照；可手动更新。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "自定义规则：$ruleVersion" + if (ruleBundleVersion > 0L) " · 规则包：$ruleBundleVersion" else " · 内置快照",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            if (ruleDescription.isNotBlank()) Text(ruleDescription, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (ruleUpdateMessage.isNotBlank()) Text(ruleUpdateMessage, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("同时更新中国基础规则与 RRBOX 自定义规则；更新失败继续使用原有规则。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onUpdateRuleSets,
                enabled = !ruleSetUpdating,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text(
                    if (ruleSetUpdating) "正在更新…" else "立即更新分流规则",
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
                        text = "关闭逐连接流向日志，仅保留必要错误与少量状态信息。分流规则继续生效；关闭智能分流时也会减少额外流量检查。",
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

        SettingsCard(borderHighlighted = tunEngine != PreferencesManager.TUN_ENGINE_SYSTEM) {
            Text(text = "转发引擎", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when (tunEngine) {
                    PreferencesManager.TUN_ENGINE_HEV ->
                        "当前：HEV 极速引擎。Android TUN 交给 native C/lwIP，再通过本机 SOCKS5 进入 sing-box。"
                    PreferencesManager.TUN_ENGINE_ROOT ->
                        "当前：Root 引擎。需要超级用户授权，直接接管设备流量，不占用 Android VPN 槽位。"
                    else -> "当前：稳定引擎。使用已实机验证的 sing-box system TUN 数据面。"
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
                        enabled = !engineSwitchBusy && pendingTunEngine == null,
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
                        enabled = !engineSwitchBusy && pendingTunEngine == null,
                        modifier = Modifier.weight(1f)
                    ) { Text("HEV 极速") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (tunEngine == PreferencesManager.TUN_ENGINE_ROOT) {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) { Text("Root 模式 · 已选择", color = DarkBackground, fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(
                    onClick = { switchTunEngine(PreferencesManager.TUN_ENGINE_ROOT) },
                    enabled = !engineSwitchBusy && pendingTunEngine == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Root 模式") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "三种引擎沿用当前节点、分流与应用范围。Root 需要设备已获取超级用户权限；切回 System / HEV 时可能需要 Android VPN 授权。System 稳定模式仍为默认。",
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

        SettingsCard {
            Text(text = "Network Lab · 网络实验室", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "旁路查看网络路径、TUN/MTU、DNS、IPv4/IPv6、启动自检与进程日志。System vs HEV A/B 仅在这两种引擎下开放，Root 运行时不会参与。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { context.startActivity(Intent(context, NetworkLabActivity::class.java)) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("打开 Network Lab", color = DarkBackground, fontWeight = FontWeight.Bold)
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
            Text(text = "转发引擎: System / HEV / Root", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text(text = "运行方式: Android VPN / Root 接管", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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

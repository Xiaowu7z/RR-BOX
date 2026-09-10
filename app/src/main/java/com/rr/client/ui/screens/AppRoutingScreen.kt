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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.ui.theme.CardBorder
import com.rr.client.ui.theme.CyanPrimary
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.TextPrimary
import com.rr.client.ui.theme.TextSecondary

@Composable
fun AppRoutingScreen(
    apps: List<AppRouteConfig>,
    perAppMode: String,
    smartRouting: Boolean,
    selectedPackages: Set<String>,
    applyingRouting: Boolean,
    loadingApps: Boolean,
    selectingAutomatically: Boolean,
    onModeChanged: (String) -> Unit,
    onAutoSelect: () -> Unit,
    onAppSelectionChanged: (String, Boolean) -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val busy = applyingRouting || loadingApps || selectingAutomatically

    val sortedApps = remember(apps, selectedPackages) {
        apps.sortedWith(
            compareByDescending<AppRouteConfig> { it.packageName in selectedPackages }
                .thenBy { it.appName.lowercase() }
        )
    }
    val filteredApps = remember(sortedApps, searchQuery) {
        sortedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "应用接管范围",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                PerAppPolicyResolver.MODE_ALL to "所有应用",
                PerAppPolicyResolver.MODE_ALLOW_LIST to "仅选中应用",
                PerAppPolicyResolver.MODE_DISALLOW_LIST to "选中绕过"
            ).forEach { (mode, label) ->
                val selected = perAppMode == mode
                OutlinedButton(
                    onClick = { onModeChanged(mode) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) CyanPrimary.copy(alpha = 0.2f) else DarkSurface,
                        contentColor = if (selected) CyanPrimary else TextSecondary
                    ),
                    border = BorderStroke(1.dp, if (selected) CyanPrimary else CardBorder)
                ) {
                    Text(text = label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = when (perAppMode) {
                PerAppPolicyResolver.MODE_ALLOW_LIST -> if (smartRouting) {
                    "勾选应用按智能分流规则联网；未选应用的业务连接直接联网。这个名单与“选中绕过”独立保存。"
                } else {
                    "智能分流已关闭：勾选应用的互联网连接走代理，未选应用直接联网。选中的浏览器访问国内网站也会走代理。"
                }
                PerAppPolicyResolver.MODE_DISALLOW_LIST -> "这里勾选的应用完全绕过 RRBOX。这个名单与“仅选中代理”完全独立。"
                else -> "所有应用使用 RRBOX 分流。开启智能分流后，国内服务直连，海外服务走当前代理节点。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        // Fixed-height status row: text changes, layout does not jump.
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                loadingApps -> "正在读取应用和已保存的选择…"
                selectingAutomatically -> "正在自动选择已安装的应用…"
                applyingRouting -> "正在应用分流配置…"
                else -> "修改选择后自动保存，已连接时会重新应用一次。"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (applyingRouting) CyanPrimary else TextSecondary
        )

        if (perAppMode == PerAppPolicyResolver.MODE_ALL) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("所有应用已纳入分流", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "接管范围决定哪些应用使用 RRBOX，设置中的智能分流决定连接走直连还是代理。切换到其他范围时会恢复各自保存的名单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAutoSelect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Text(if (selectingAutomatically) "正在选择…" else "自动选择")
                }
                Text(
                    "一键勾选已安装的常用海外应用、Chrome / Edge 和谷歌后台推送服务，保留手动添加和取消。新装应用后可再点一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (apps.any { it.packageName == "com.google.android.gms" }) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if ("com.google.android.gms" in selectedPackages) {
                            "谷歌后台推送：已选中 Google Play 服务；直连可用时也可取消。"
                        } else {
                            "谷歌后台推送：未选中 Google Play 服务；直连可用时无需勾选，可按需手动开启。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if ("com.google.android.gms" in selectedPackages) CyanPrimary else TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "已选择 ${selectedPackages.size} 个应用 · 已选应用自动置顶",
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索应用或包名…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = CardBorder
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selectedPackages
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, if (checked) CyanPrimary else CardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                if (app.packageName == "com.google.android.gms") {
                                    Text(
                                        text = "谷歌后台推送（FCM）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanPrimary
                                    )
                                }
                            }
                            Switch(
                                checked = checked,
                                enabled = !busy,
                                onCheckedChange = { enabled -> onAppSelectionChanged(app.packageName, enabled) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = DarkBackground,
                                    checkedTrackColor = CyanPrimary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

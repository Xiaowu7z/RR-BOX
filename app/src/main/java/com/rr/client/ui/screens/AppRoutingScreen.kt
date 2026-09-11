package com.rr.client.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onAppSelectionChanged: (String, Boolean) -> Unit,
    onOpenAppNodeRouting: () -> Unit = {},
    activeAppNodeBindingCount: Int = 0
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(showDetails) {
        if (showDetails) listState.animateScrollToItem(0)
    }
    val busy = applyingRouting || loadingApps || selectingAutomatically
    val showAppList = perAppMode != PerAppPolicyResolver.MODE_ALL
    val statusText = when {
        loadingApps -> "正在读取应用…"
        selectingAutomatically -> "正在自动选择…"
        applyingRouting -> "正在应用配置…"
        showAppList -> "已选 ${selectedPackages.size} 个应用"
        else -> "所有应用"
    }

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
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "应用接管范围",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = onOpenAppNodeRouting,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = CyanPrimary)
            ) {
                Text(if (activeAppNodeBindingCount > 0) "指定节点 · $activeAppNodeBindingCount" else "指定节点")
            }
            IconButton(onClick = { showDetails = !showDetails }) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = if (showDetails) "收起使用说明" else "展开使用说明",
                    tint = if (showDetails) CyanPrimary else TextSecondary
                )
            }
        }

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
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
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

        if (showAppList) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = CyanPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST) {
                    OutlinedButton(
                        onClick = onAutoSelect,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Text(if (selectingAutomatically) "正在选择…" else "自动选择")
                    }
                }
            }

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
            Spacer(modifier = Modifier.height(8.dp))
        } else if (busy) {
            Text(
                text = statusText,
                modifier = Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary
            )
        }

        // Only the compact controls stay fixed. Expanded help scrolls with the apps.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (showDetails) {
                item(key = "routing_help", contentType = "help") {
                    AppRoutingDetails(
                        perAppMode = perAppMode,
                        smartRouting = smartRouting,
                        hasGooglePlayServices = apps.any { it.packageName == "com.google.android.gms" },
                        googlePlayServicesSelected = "com.google.android.gms" in selectedPackages
                    )
                }
            }

            if (!showAppList) {
                item(key = "all_apps", contentType = "status") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("所有应用已纳入接管", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (smartRouting) "智能分流已开启，连接按规则使用直连或代理。"
                                else "智能分流已关闭，应用的互联网连接走代理。",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else if (filteredApps.isEmpty()) {
                item(key = "empty_apps", contentType = "status") {
                    Text(
                        text = when {
                            loadingApps -> "正在读取应用列表…"
                            searchQuery.isNotBlank() -> "没有找到匹配的应用"
                            else -> "暂无可显示的应用"
                        },
                        modifier = Modifier.padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            } else {
                items(filteredApps, key = { it.packageName }, contentType = { "app" }) { app ->
                    val checked = app.packageName in selectedPackages
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, if (checked) CyanPrimary else CardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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

@Composable
private fun AppRoutingDetails(
    perAppMode: String,
    smartRouting: Boolean,
    hasGooglePlayServices: Boolean,
    googlePlayServicesSelected: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when (perAppMode) {
                    PerAppPolicyResolver.MODE_ALLOW_LIST -> if (smartRouting) {
                        "勾选应用按智能分流规则联网；未选应用的业务连接直接联网。这个名单与“选中绕过”独立保存。"
                    } else {
                        "智能分流已关闭：勾选应用的互联网连接走代理，未选应用直接联网。选中的浏览器访问国内网站也会走代理。"
                    }
                    PerAppPolicyResolver.MODE_DISALLOW_LIST ->
                        "这里勾选的应用完全绕过 RRBOX。这个名单与“仅选中应用”独立保存。"
                    else ->
                        "接管范围决定哪些应用使用 RRBOX，设置中的智能分流决定连接走直连还是代理。切换范围会恢复各自保存的名单。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                "修改选择后自动保存，已连接时会重新应用一次。已选应用自动置顶。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Text(
                "指定节点可为接管范围内的应用单独选择出口，其他应用继续使用主节点。该设置独立于智能分流。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST) {
                Text(
                    "自动选择会勾选已安装的常用海外应用、Chrome / Edge 和谷歌后台推送服务，保留手动添加和取消。新装应用后可再点一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                if (hasGooglePlayServices) {
                    Text(
                        text = if (googlePlayServicesSelected) {
                            "谷歌后台推送：已选中 Google Play 服务；直连可用时也可取消。"
                        } else {
                            "谷歌后台推送：未选中 Google Play 服务；直连可用时无需勾选，可按需手动开启。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (googlePlayServicesSelected) CyanPrimary else TextSecondary
                    )
                }
            }
        }
    }
}

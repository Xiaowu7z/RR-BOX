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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rr.client.core.model.AppRouteConfig
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
    selectedPackages: Set<String>,
    applyingRouting: Boolean,
    onModeChanged: (String) -> Unit,
    onAppSelectionChanged: (String, Boolean) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

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
            text = "分应用代理",
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
                "ALL" to "全部代理",
                "ALLOW_LIST" to "仅选中代理",
                "DISALLOW_LIST" to "选中绕过"
            ).forEach { (mode, label) ->
                val selected = perAppMode == mode
                OutlinedButton(
                    onClick = { onModeChanged(mode) },
                    enabled = !applyingRouting,
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
                "ALLOW_LIST" -> "只有勾选的应用进入 RRBOX；其它应用直接使用系统网络。"
                "DISALLOW_LIST" -> "勾选的应用完全绕过 RRBOX；其它应用进入 VPN。"
                else -> "所有应用进入 RRBOX。全局模式不需要维护应用清单。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        if (applyingRouting) {
            Spacer(Modifier.height(4.dp))
            Text("正在自动重建 VPN…", style = MaterialTheme.typography.labelMedium, color = CyanPrimary)
        }

        if (perAppMode == "ALL") {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("全局模式已启用", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "应用选择列表在此模式下隐藏；切换到“仅选中代理”或“选中绕过”后会恢复之前保存的选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            return@Column
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "已选择 ${selectedPackages.size} 个应用 · 已选择应用自动置顶",
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
                        }
                        Switch(
                            checked = checked,
                            enabled = !applyingRouting,
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

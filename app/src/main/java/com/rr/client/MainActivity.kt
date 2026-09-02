package com.rr.client

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import com.rr.client.core.model.ProtocolType
import com.rr.client.routing.AppManager
import com.rr.client.subscription.SubscriptionFetcher
import com.rr.client.subscription.model.SubscriptionUserInfo
import com.rr.client.ui.screens.*
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.RRClientTheme
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInternal()
        } else {
            Toast.makeText(this, "VPN 授权未通过", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingConfigJson: String? = null
    private var pendingNodeTag: String? = null
    private var pendingNodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RRClientTheme {
                MainApp()
            }
        }
    }

    @Composable
    fun MainApp() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val isVpnRunning by RRVpnService.isRunning.collectAsState()
        val currentSpeed by RRVpnService.currentSpeed.collectAsState()
        val sessionTraffic by RRVpnService.sessionTraffic.collectAsState()
        var nodes by remember {
            mutableStateOf(
                listOf(
                    ProxyNode("1", "日本 HY2 (直连高速)", ProtocolType.HYSTERIA2, "jp.rrvps.net", 443, "rr-pass-sample", sni = "jp.rrvps.net"),
                    ProxyNode("2", "洛杉矶 VLESS Reality", ProtocolType.VLESS_REALITY, "la.rrvps.net", 443, "3b6007dd-b8e2-4ed2-8b9f-300a6da02114", realityPublicKey = "Lv58KCuRO4Fz4rXi9fjykxiKetY50g84kCwiOUbrwVw", realityShortId = "63a64eb3", sni = "apple.com"),
                    ProxyNode("3", "香港 TUIC v5", ProtocolType.TUIC_V5, "hk.rrvps.net", 8443, "3b6007dd-b8e2-4ed2-8b9f-300a6da02114", sni = "hk.rrvps.net")
                )
            )
        }
        var selectedNodeId by remember { mutableStateOf("1") }
        val selectedNode = nodes.find { it.id == selectedNodeId } ?: nodes.firstOrNull()
        var userInfo by remember {
            mutableStateOf(
                SubscriptionUserInfo(
                    upload = 5_000_000_000L,
                    download = 45_000_000_000L,
                    total = 500_000_000_000L,
                    expireTimestamp = System.currentTimeMillis() / 1000L + 86400 * 30
                )
            )
        }
        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }
        var smartRouting by remember { mutableStateOf(true) }
        var perAppMode by remember { mutableStateOf("ALL") }

        LaunchedEffect(Unit) {
            val appMgr = AppManager(this@MainActivity)
            apps = appMgr.getInstalledApps(includeSystem = false)
        }

        Scaffold(
            containerColor = DarkBackground,
            bottomBar = {
                NavigationBar(containerColor = DarkSurface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Speed, contentDescription = "仪表盘") },
                        label = { Text("仪表盘") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Dns, contentDescription = "节点") },
                        label = { Text("节点") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Apps, contentDescription = "分流") },
                        label = { Text("分流") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.CloudDownload, contentDescription = "订阅") },
                        label = { Text("订阅") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 4,
                        onClick = { selectedTab = 4 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
        label = { Text("设置") }
                    )
                }
            }
        ) { paddingValues ->
            Surface(modifier = Modifier.padding(paddingValues), color = DarkBackground) {
                when (selectedTab) {
                    0 -> DashboardScreen(
                        isConnected = isVpnRunning,
                        currentSpeed = currentSpeed,
                        sessionTraffic = sessionTraffic,
                        userInfo = userInfo,
                        selectedNode = selectedNode,
                        onToggleVpn = {
                            if (isVpnRunning) {
                                val stopIntent = Intent(this@MainActivity, RRVpnService::class.java).apply {
                                    action = com.rr.client.vpn.RRNotificationManager.ACTION_STOP_VPN
                                }
                                startService(stopIntent)
                            } else {
                                selectedNode?.let { node ->
                                    val configJson = ConfigBuilder.buildSingBoxConfig(
                                        selectedNode = node,
                                        allNodes = nodes,
                                        appRoutes = apps,
                                        smartRouting = smartRouting
                                    )
                                    startVpnWithPermissionCheck(configJson, node.tag, node.id)
                                }
                            }
                        },
                        onNavigateToNodes = { selectedTab = 1 }
                    )
                    1 -> NodeListScreen(
                        nodes = nodes,
                        selectedNodeId = selectedNodeId,
                        onSelectNode = { node ->
                            selectedNodeId = node.id
                            Toast.makeText(this@MainActivity, "已切换节点: ${node.tag}", Toast.LENGTH_SHORT).show()
                        }
                    )
                    2 -> AppRoutingScreen(
                        apps = apps,
                        perAppMode = perAppMode,
                        onModeChanged = { perAppMode = it },
                        onAppRouteChanged = { updatedApp ->
                            apps = apps.map { if (it.packageName == updatedApp.packageName) updatedApp else it }
                        }
                    )
                    3 -> SubscriptionScreen(
                        initialUrl = "https://sub.rrvps.net/api/v1/client/subscribe?token=demo",
                        onFetchSubscription = { url ->
                            lifecycleScope.launch {
                                val fetcher = SubscriptionFetcher()
                                val result = fetcher.fetchSubscription(url)
                                result.onSuccess { (newNodes, newUserInfo) ->
                                    nodes = newNodes
                                    userInfo = newUserInfo
                                    Toast.makeText(this@MainActivity, "成功同步 ${newNodes.size} 个节点", Toast.LENGTH_SHORT).show()
                                }.onFailure { e ->
                                    Toast.makeText(this@MainActivity, "订阅更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                    4 -> SettingsScreen(
                        smartRouting = smartRouting,
                        onSmartRoutingChanged = { smartRouting = it }
                    )
                }
            }
        }
    }

    private fun startVpnWithPermissionCheck(configJson: String, nodeTag: String, nodeId: String) {
        pendingConfigJson = configJson
        pendingNodeTag = nodeTag
        pendingNodeId = nodeId

        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            // Permission already granted, start VPN directly
            startVpnServiceInternal()
        }
    }

    private fun startVpnServiceInternal() {
        val config = pendingConfigJson ?: return
        val tag = pendingNodeTag ?: "Node"
        val id = pendingNodeId ?: ""

        val serviceIntent = Intent(this, RRVpnService::class.java).apply {
            putExtra(RRVpnService.EXTRA_CONFIG_JSON, config)
            putExtra(RRVpnService.EXTRA_NODE_TAG, tag)
            putExtra(RRVpnService.EXTRA_NODE_ID, id)
        }
        startForegroundService(serviceIntent)
    }
}
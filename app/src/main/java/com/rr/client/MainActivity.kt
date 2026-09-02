package com.rr.client

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import io.nekohasekai.libbox.Libbox
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
        val isVpnStarting by RRVpnService.isStarting.collectAsState()
        val lastVpnError by RRVpnService.lastError.collectAsState()
        val currentSpeed by RRVpnService.currentSpeed.collectAsState()
        val sessionTraffic by RRVpnService.sessionTraffic.collectAsState()

        LaunchedEffect(lastVpnError) {
            lastVpnError?.takeIf { it.isNotBlank() }?.let { message ->
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }

        // Never ship fake credentials as connectable nodes. A real node must
        // come from the user's RRVPS subscription before the VPN can start.
        var nodes by remember { mutableStateOf<List<ProxyNode>>(emptyList()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        val selectedNode = nodes.find { it.id == selectedNodeId } ?: nodes.firstOrNull()

        var userInfo by remember { mutableStateOf<SubscriptionUserInfo?>(null) }

        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }
        var smartRouting by remember { mutableStateOf(true) }
        var perAppMode by remember { mutableStateOf("ALL") }

        // Loading every installed package is relatively expensive on large
        // phones. Defer it until the user actually opens the per-app page;
        // the first connectivity test therefore starts with no package rules.
        LaunchedEffect(selectedTab) {
            if (selectedTab == 2 && apps.isEmpty()) {
                val appMgr = AppManager(this@MainActivity)
                apps = appMgr.getInstalledApps(includeSystem = false)
            }
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
                        isStarting = isVpnStarting,
                        onToggleVpn = {
                            when {
                                isVpnRunning -> {
                                    val stopIntent = Intent(this@MainActivity, RRVpnService::class.java).apply {
                                        action = com.rr.client.vpn.RRNotificationManager.ACTION_STOP_VPN
                                    }
                                    startService(stopIntent)
                                }

                                isVpnStarting -> {
                                    Toast.makeText(this@MainActivity, "正在建立 VPN，请稍候", Toast.LENGTH_SHORT).show()
                                }

                                selectedNode == null -> {
                                    selectedTab = 3
                                    Toast.makeText(this@MainActivity, "请先导入 RRVPS 订阅", Toast.LENGTH_LONG).show()
                                }

                                else -> {
                                    selectedNode?.let { node ->
                                        lifecycleScope.launch {
                                            val result = withContext(Dispatchers.Default) {
                                                runCatching {
                                                    val config = ConfigBuilder.buildSingBoxConfig(
                                                        selectedNode = node,
                                                        allNodes = nodes,
                                                        appRoutes = apps,
                                                        smartRouting = smartRouting
                                                    )
                                                    Libbox.checkConfig(config)
                                                    config
                                                }
                                            }
                                            result.onSuccess { configJson ->
                                                startVpnWithPermissionCheck(configJson, node.tag, node.id)
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "配置校验失败: ${error.message ?: error.javaClass.simpleName}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
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
                        initialUrl = "",
                        onFetchSubscription = { url ->
                            lifecycleScope.launch {
                                val fetcher = SubscriptionFetcher()
                                val result = fetcher.fetchSubscription(url)
                                result.onSuccess { (newNodes, newUserInfo) ->
                                    nodes = newNodes
                                    selectedNodeId = newNodes.firstOrNull()?.id
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
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            pendingConfigJson = null
            pendingNodeTag = null
            pendingNodeId = null
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                "无法启动 VPN 服务: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

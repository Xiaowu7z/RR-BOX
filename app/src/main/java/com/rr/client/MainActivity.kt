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
import androidx.lifecycle.lifecycleScope
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.routing.AppManager
import com.rr.client.subscription.SubscriptionFetcher
import com.rr.client.subscription.model.SubProfile
import com.rr.client.ui.screens.*
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.RRClientTheme
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

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

        val db = RRApplication.instance.database
        val prefs = RRApplication.instance.preferencesManager

        // ------- 订阅组 / 节点（无任何内置预设，全部来自用户添加的订阅） -------
        var subProfiles by remember { mutableStateOf<List<SubProfile>>(emptyList()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        var smartRouting by remember { mutableStateOf(true) }
        var perAppMode by remember { mutableStateOf("ALL") }
        var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var addingProfile by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }

        val allNodes = remember(subProfiles) {
            subProfiles.flatMap { it.nodes }
        }
        val selectedNode = allNodes.find { it.id == selectedNodeId }
        val selectedProfile = subProfiles.firstOrNull { p -> p.nodes.any { it.id == selectedNodeId } }

        // 首次进入：加载本地订阅数据与偏好
        LaunchedEffect(Unit) {
            val loadedProfiles = withContext(Dispatchers.IO) {
                db.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
            }
            subProfiles = loadedProfiles

            val storedId = runCatching { prefs.selectedNodeId.first() }.getOrNull()
            smartRouting = runCatching { prefs.smartRouting.first() }.getOrDefault(true)
            perAppMode = runCatching { prefs.perAppMode.first() }.getOrDefault("ALL")

            val nodesNow = loadedProfiles.flatMap { it.nodes }
            val resolved = if (nodesNow.any { it.id == storedId }) {
                storedId
            } else {
                nodesNow.firstOrNull()?.id
            }
            selectedNodeId = resolved
            if (resolved != null) {
                prefs.setSelectedNodeId(resolved)
            }

            val appMgr = AppManager(this@MainActivity)
            apps = withContext(Dispatchers.IO) { appMgr.getInstalledApps(includeSystem = false) }
        }

        fun refreshFromProfiles(updated: List<SubProfile>) {
            subProfiles = updated
            val nodesNow = updated.flatMap { it.nodes }
            val current = selectedNodeId
            val resolved = if (nodesNow.any { it.id == current }) current
            else nodesNow.firstOrNull()?.id
            if (resolved != current) {
                selectedNodeId = resolved
                if (resolved != null) {
                    lifecycleScope.launch { prefs.setSelectedNodeId(resolved) }
                }
            }
        }

        fun toast(text: String) =
            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()

        fun addProfile(name: String, url: String) {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) {
                toast("请填写订阅链接")
                return
            }
            addingProfile = true
            val profileId = UUID.randomUUID().toString().substring(0, 8)
            val profileName = name.trim().ifEmpty { "订阅 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}" }
            lifecycleScope.launch {
                val fetcher = SubscriptionFetcher()
                val result = fetcher.fetchSubscription(trimmedUrl, profileId, profileName)
                addingProfile = false
                result.onSuccess { (newNodes, userInfo) ->
                    val profile = SubProfile(
                        id = profileId,
                        name = profileName,
                        url = trimmedUrl,
                        lastUpdated = System.currentTimeMillis(),
                        nodes = newNodes,
                        userInfo = userInfo
                    )
                    withContext(Dispatchers.IO) { db.profileDao().insertProfile(profile.toEntity()) }
                    refreshFromProfiles(subProfiles + profile)
                    toast("「$profileName」同步成功：${newNodes.size} 个节点")
                }.onFailure { e ->
                    toast("添加订阅失败：${e.message ?: "网络错误"}")
                }
            }
        }

        fun refreshProfile(profileId: String) {
            val existing = subProfiles.find { it.id == profileId } ?: return
            refreshingIds = refreshingIds + profileId
            lifecycleScope.launch {
                val fetcher = SubscriptionFetcher()
                val result = fetcher.fetchSubscription(existing.url, existing.id, existing.name)
                refreshingIds = refreshingIds - profileId
                result.onSuccess { (newNodes, userInfo) ->
                    val updated = existing.copy(
                        lastUpdated = System.currentTimeMillis(),
                        nodes = newNodes,
                        userInfo = userInfo
                    )
                    withContext(Dispatchers.IO) { db.profileDao().insertProfile(updated.toEntity()) }
                    refreshFromProfiles(subProfiles.map { if (it.id == profileId) updated else it })
                    toast("「${existing.name}」更新成功：${newNodes.size} 个节点")
                }.onFailure { e ->
                    toast("「${existing.name}」更新失败：${e.message ?: "网络错误"}")
                }
            }
        }

        fun deleteProfile(profileId: String) {
            val existing = subProfiles.find { it.id == profileId } ?: return
            if (isVpnRunning) {
                toast("请先断开连接再删除订阅")
                return
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.profileDao().deleteProfile(existing.toEntity()) }
                refreshFromProfiles(subProfiles.filterNot { it.id == profileId })
                toast("已删除订阅「${existing.name}」")
            }
        }

        fun selectNode(nodeId: String) {
            selectedNodeId = nodeId
            lifecycleScope.launch { prefs.setSelectedNodeId(nodeId) }
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
                        userInfo = selectedProfile?.userInfo,
                        profileName = selectedProfile?.name,
                        selectedNode = selectedNode,
                        onToggleVpn = onToggle@{
                            if (isVpnRunning) {
                                val stopIntent = Intent(this@MainActivity, RRVpnService::class.java).apply {
                                    action = com.rr.client.vpn.RRNotificationManager.ACTION_STOP_VPN
                                }
                                startService(stopIntent)
                            } else {
                                val targetNode = selectedNode ?: allNodes.firstOrNull()
                                if (targetNode == null) {
                                    toast("还没有任何节点：请先到「订阅」页添加订阅链接并同步")
                                    selectedTab = 3
                                    return@onToggle
                                }
                                if (selectedNode == null && targetNode != null) {
                                    selectNode(targetNode.id)
                                }
                                try {
                                    val configJson = ConfigBuilder.buildSingBoxConfig(
                                        selectedNode = targetNode,
                                        allNodes = allNodes,
                                        appRoutes = apps,
                                        smartRouting = smartRouting
                                    )
                                    startVpnWithPermissionCheck(configJson, targetNode.tag, targetNode.id)
                                } catch (e: Exception) {
                                    toast("无法连接：${e.message ?: "配置生成失败"}")
                                }
                            }
                        },
                        onNavigateToNodes = { selectedTab = 1 }
                    )
                    1 -> NodeListScreen(
                        nodes = allNodes,
                        selectedNodeId = selectedNodeId,
                        onSelectNode = { node ->
                            selectNode(node.id)
                            toast("已切换节点：${node.tag}")
                        },
                        onGoToSubscription = { selectedTab = 3 }
                    )
                    2 -> AppRoutingScreen(
                        apps = apps,
                        perAppMode = perAppMode,
                        onModeChanged = {
                            perAppMode = it
                            lifecycleScope.launch { prefs.setPerAppMode(it) }
                        },
                        onAppRouteChanged = { updatedApp ->
                            apps = apps.map { if (it.packageName == updatedApp.packageName) updatedApp else it }
                        }
                    )
                    3 -> SubscriptionScreen(
                        profiles = subProfiles,
                        busyIds = refreshingIds,
                        adding = addingProfile,
                        onAddProfile = { name, url -> addProfile(name, url) },
                        onRefreshProfile = { id -> refreshProfile(id) },
                        onDeleteProfile = { id -> deleteProfile(id) }
                    )
                    4 -> SettingsScreen(
                        smartRouting = smartRouting,
                        onSmartRoutingChanged = {
                            smartRouting = it
                            lifecycleScope.launch { prefs.setSmartRouting(it) }
                        }
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

package com.rr.client

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.NodeLatencyTester
import com.rr.client.core.NodeOverridePatcher
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.AppManager
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.security.PinSecurity
import com.rr.client.subscription.SubscriptionFetcher
import com.rr.client.subscription.model.SubProfile
import com.rr.client.ui.components.NodeEditDialog
import com.rr.client.ui.components.PinSetupDialog
import com.rr.client.ui.components.PinUnlockScreen
import com.rr.client.ui.screens.AppRoutingScreen
import com.rr.client.ui.screens.DashboardScreen
import com.rr.client.ui.screens.NodeListScreen
import com.rr.client.ui.screens.SettingsScreen
import com.rr.client.ui.screens.SubscriptionScreen
import com.rr.client.ui.theme.DarkBackground
import com.rr.client.ui.theme.DarkSurface
import com.rr.client.ui.theme.RRClientTheme
import com.rr.client.update.AppUpdateChecker
import com.rr.client.vpn.RRNotificationManager
import com.rr.client.vpn.RRVpnService
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val backgroundOptimizationExempt = MutableStateFlow(false)
    private val appUnlocked = MutableStateFlow<Boolean?>(null)
    private var pinEnabledCached = false
    private var routingRestartJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) {
                "通知权限已开启：连接后会显示实时速度"
            } else {
                "通知权限未开启：VPN 仍可使用，但通知栏不会显示实时速度"
            },
            Toast.LENGTH_LONG
        ).show()
        requestBackgroundProtectionGuideIfNeeded()
    }

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnServiceInternal()
        } else {
            clearPendingVpn()
            Toast.makeText(this, "VPN 授权未通过", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingConfigJson: String? = null
    private var pendingNodeTag: String? = null
    private var pendingNodeId: String? = null
    private var pendingPerAppMode: String = PerAppPolicyResolver.MODE_ALL
    private var pendingSelectedPackages: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateBackgroundProtectionState()
        setContent {
            RRClientTheme {
                SecurityGate()
            }
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundProtectionState()
    }

    override fun onStop() {
        super.onStop()
        if (pinEnabledCached && !isChangingConfigurations) {
            appUnlocked.value = false
        }
    }

    @Composable
    private fun SecurityGate() {
        val prefs = RRApplication.instance.preferencesManager
        val unlocked by appUnlocked.collectAsState()
        var pinEnabled by remember { mutableStateOf<Boolean?>(null) }
        var verifying by remember { mutableStateOf(false) }
        var unlockError by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            prefs.pinEnabled.collect { enabled ->
                val firstValue = pinEnabled == null
                pinEnabled = enabled
                pinEnabledCached = enabled
                when {
                    !enabled -> appUnlocked.value = true
                    firstValue || appUnlocked.value == null -> appUnlocked.value = false
                }
            }
        }

        when {
            pinEnabled == null || unlocked == null -> {
                Surface(modifier = Modifier.fillMaxSize(), color = DarkBackground) {}
            }

            pinEnabled == true && unlocked != true -> {
                PinUnlockScreen(
                    verifying = verifying,
                    errorMessage = unlockError,
                    onUnlock = { pin ->
                        lifecycleScope.launch {
                            if (verifying) return@launch
                            verifying = true
                            val salt = runCatching { prefs.pinSalt.first() }.getOrNull()
                            val hash = runCatching { prefs.pinHash.first() }.getOrNull()
                            val valid = withContext(Dispatchers.Default) {
                                PinSecurity.verify(pin, salt, hash)
                            }
                            verifying = false
                            if (valid) {
                                unlockError = null
                                appUnlocked.value = true
                            } else {
                                unlockError = "PIN 不正确"
                            }
                        }
                    }
                )
            }

            else -> MainApp()
        }
    }

    @Composable
    private fun MainApp() {
        var selectedTab by remember { mutableIntStateOf(0) }
        val isVpnRunning by RRVpnService.isRunning.collectAsState()
        val isVpnStarting by RRVpnService.isStarting.collectAsState()
        val lastVpnError by RRVpnService.lastError.collectAsState()
        val currentSpeed by RRVpnService.currentSpeed.collectAsState()
        val sessionTraffic by RRVpnService.sessionTraffic.collectAsState()
        val backgroundProtected by backgroundOptimizationExempt.collectAsState()

        val db = RRApplication.instance.database
        val prefs = RRApplication.instance.preferencesManager
        val nodeOverrides by prefs.nodeOverrides.collectAsState(initial = emptyMap())
        val selectedAppPackages by prefs.selectedAppPackages.collectAsState(initial = emptySet())
        val pinEnabled by prefs.pinEnabled.collectAsState(initial = false)
        val ruleSetLastUpdated by prefs.chinaRuleSetLastUpdated.collectAsState(initial = 0L)

        var subProfiles by remember { mutableStateOf<List<SubProfile>>(emptyList()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        var smartRouting by remember { mutableStateOf(true) }
        var perAppMode by remember { mutableStateOf(PerAppPolicyResolver.MODE_ALL) }
        var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var addingProfile by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }
        var latencyStates by remember { mutableStateOf<Map<String, NodeLatencyState>>(emptyMap()) }
        var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
        var applyingRouting by remember { mutableStateOf(false) }
        var pendingReconnectAfterSelection by remember { mutableStateOf(false) }
        var updatingRuleSets by remember { mutableStateOf(false) }
        var checkingAppUpdate by remember { mutableStateOf(false) }
        var showPinSetup by remember { mutableStateOf(false) }

        val baseNodes = remember(subProfiles) { subProfiles.flatMap { it.nodes } }
        val allNodes = remember(baseNodes, nodeOverrides) {
            baseNodes.map { base -> nodeOverrides[base.id] ?: base }
        }
        val selectedNode = allNodes.find { it.id == selectedNodeId }
        val selectedProfile = subProfiles.firstOrNull { p -> p.nodes.any { it.id == selectedNodeId } }

        LaunchedEffect(lastVpnError) {
            val message = lastVpnError?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            Toast.makeText(this@MainActivity, "连接失败：$message", Toast.LENGTH_LONG).show()
            RRVpnService.clearLastError()
        }

        LaunchedEffect(Unit) {
            val loadedProfiles = withContext(Dispatchers.IO) {
                db.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
            }
            subProfiles = loadedProfiles

            val storedId = runCatching { prefs.selectedNodeId.first() }.getOrNull()
            smartRouting = runCatching { prefs.smartRouting.first() }.getOrDefault(true)
            perAppMode = runCatching { prefs.perAppMode.first() }
                .getOrDefault(PerAppPolicyResolver.MODE_ALL)

            val nodesNow = loadedProfiles.flatMap { it.nodes }
            val resolved = if (nodesNow.any { it.id == storedId }) storedId else nodesNow.firstOrNull()?.id
            selectedNodeId = resolved
            if (resolved != null) prefs.setSelectedNodeId(resolved)

            val appMgr = AppManager(this@MainActivity)
            apps = withContext(Dispatchers.IO) { appMgr.getInstalledApps(includeSystem = false) }

            withContext(Dispatchers.IO) {
                ChinaRuleSetManager.ensureBundled(this@MainActivity)
            }
        }

        fun refreshFromProfiles(updated: List<SubProfile>) {
            subProfiles = updated
            val nodesNow = updated.flatMap { it.nodes }
            val current = selectedNodeId
            val resolved = if (nodesNow.any { it.id == current }) current else nodesNow.firstOrNull()?.id
            if (resolved != current) {
                selectedNodeId = resolved
                if (resolved != null) lifecycleScope.launch { prefs.setSelectedNodeId(resolved) }
            }
            latencyStates = latencyStates.filterKeys { id -> nodesNow.any { it.id == id } }
        }

        fun toast(text: String) = Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()

        fun currentTargetNode(): ProxyNode? = selectedNode ?: allNodes.firstOrNull()

        fun scheduleRoutingApply(
            mode: String,
            packages: Set<String>,
            smart: Boolean,
            reconnectWhenStopped: Boolean = false
        ) {
            val node = currentTargetNode() ?: return
            routingRestartJob?.cancel()
            routingRestartJob = lifecycleScope.launch {
                applyingRouting = true
                delay(250L)

                if (mode == PerAppPolicyResolver.MODE_ALLOW_LIST && packages.isEmpty()) {
                    if (RRVpnService.isRunning.value || RRVpnService.isStarting.value) {
                        sendStopVpn()
                    }
                    pendingReconnectAfterSelection = true
                    applyingRouting = false
                    toast("仅选中代理模式需要至少选择 1 个应用；当前 VPN 已安全断开")
                    return@launch
                }

                val result = buildRuntimeConfig(node, allNodes, apps, smart)
                result.onSuccess { config ->
                    when {
                        RRVpnService.isRunning.value || RRVpnService.isStarting.value -> {
                            sendRestartVpn(config, node.tag, node.id, mode, packages)
                        }
                        reconnectWhenStopped -> {
                            startVpnWithPermissionCheck(config, node.tag, node.id, mode, packages)
                        }
                    }
                }.onFailure { error ->
                    toast("分流配置失败：${error.message ?: error.javaClass.simpleName}")
                }
                delay(450L)
                applyingRouting = false
            }
        }

        fun addProfile(name: String, url: String) {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) {
                toast("请填写订阅链接")
                return
            }
            addingProfile = true
            val profileId = UUID.randomUUID().toString().substring(0, 8)
            val profileName = name.trim().ifEmpty {
                "订阅 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
            }
            lifecycleScope.launch {
                val result = SubscriptionFetcher().fetchSubscription(trimmedUrl, profileId, profileName)
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
                val result = SubscriptionFetcher().fetchSubscription(existing.url, existing.id, existing.name)
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
            if (isVpnRunning || isVpnStarting) {
                toast("请先断开连接再删除订阅")
                return
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.profileDao().deleteProfile(existing.toEntity()) }
                existing.nodes.forEach { prefs.clearNodeOverride(it.id) }
                refreshFromProfiles(subProfiles.filterNot { it.id == profileId })
                toast("已删除订阅「${existing.name}」")
            }
        }

        fun selectNode(nodeId: String) {
            selectedNodeId = nodeId
            lifecycleScope.launch { prefs.setSelectedNodeId(nodeId) }
        }

        fun pingNode(node: ProxyNode) {
            if (isVpnRunning || isVpnStarting) {
                toast("请先断开 VPN 再测速，避免当前代理影响 Ping 结果")
                return
            }
            if (latencyStates[node.id] == NodeLatencyState.Testing) return
            latencyStates = latencyStates + (node.id to NodeLatencyState.Testing)
            lifecycleScope.launch {
                val result = NodeLatencyTester.ping(node.server)
                latencyStates = latencyStates + (node.id to result)
            }
        }

        fun pingAllNodes() {
            if (isVpnRunning || isVpnStarting) {
                toast("请先断开 VPN 再测速，避免当前代理影响 Ping 结果")
                return
            }
            if (allNodes.isEmpty()) return
            latencyStates = latencyStates + allNodes.associate { it.id to NodeLatencyState.Testing }
            lifecycleScope.launch {
                allNodes.chunked(3).forEach { batch ->
                    val batchResults = batch.map { node ->
                        async { node.id to NodeLatencyTester.ping(node.server) }
                    }.awaitAll()
                    batchResults.forEach { (id, result) ->
                        latencyStates = latencyStates + (id to result)
                    }
                }
            }
        }

        Scaffold(
            containerColor = DarkBackground,
            bottomBar = {
                NavigationBar(containerColor = DarkSurface) {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Speed, "仪表盘") }, label = { Text("仪表盘") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Dns, "节点") }, label = { Text("节点") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Apps, "分流") }, label = { Text("分流") })
                    NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.CloudDownload, "订阅") }, label = { Text("订阅") })
                    NavigationBarItem(selected = selectedTab == 4, onClick = { selectedTab = 4 }, icon = { Icon(Icons.Default.Settings, "设置") }, label = { Text("设置") })
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
                            when {
                                isVpnRunning -> sendStopVpn()
                                isVpnStarting -> toast("VPN 正在启动，请稍候")
                                else -> {
                                    val targetNode = currentTargetNode()
                                    if (targetNode == null) {
                                        toast("还没有任何节点：请先到「订阅」页添加订阅链接并同步")
                                        selectedTab = 3
                                        return@onToggle
                                    }
                                    if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST && selectedAppPackages.isEmpty()) {
                                        toast("当前是「仅选中代理」模式，请先到「分流」页至少选择 1 个应用")
                                        selectedTab = 2
                                        return@onToggle
                                    }
                                    if (selectedNode == null) selectNode(targetNode.id)

                                    lifecycleScope.launch {
                                        val result = buildRuntimeConfig(targetNode, allNodes, apps, smartRouting)
                                        result.onSuccess { configJson ->
                                            startVpnWithPermissionCheck(
                                                configJson,
                                                targetNode.tag,
                                                targetNode.id,
                                                perAppMode,
                                                selectedAppPackages
                                            )
                                        }.onFailure { error ->
                                            toast("配置校验失败：${error.message ?: error.javaClass.simpleName}")
                                        }
                                    }
                                }
                            }
                        },
                        onNavigateToNodes = { selectedTab = 1 }
                    )

                    1 -> NodeListScreen(
                        nodes = allNodes,
                        selectedNodeId = selectedNodeId,
                        latencyStates = latencyStates,
                        editedNodeIds = nodeOverrides.keys,
                        onSelectNode = { node ->
                            selectNode(node.id)
                            toast("已切换节点：${node.tag}")
                        },
                        onPingAll = ::pingAllNodes,
                        onPingNode = ::pingNode,
                        onEditNode = { node -> editingNode = node },
                        onResetNodeEdit = { node ->
                            lifecycleScope.launch {
                                prefs.clearNodeOverride(node.id)
                                toast("已恢复订阅中的原始节点参数")
                            }
                        },
                        onGoToSubscription = { selectedTab = 3 }
                    )

                    2 -> AppRoutingScreen(
                        apps = apps,
                        perAppMode = perAppMode,
                        selectedPackages = selectedAppPackages,
                        applyingRouting = applyingRouting,
                        onModeChanged = { mode ->
                            perAppMode = mode
                            lifecycleScope.launch { prefs.setPerAppMode(mode) }
                            scheduleRoutingApply(mode, selectedAppPackages, smartRouting)
                        },
                        onAppSelectionChanged = { packageName, selected ->
                            val updated = selectedAppPackages.toMutableSet().apply {
                                if (selected) add(packageName) else remove(packageName)
                            }.toSet()
                            lifecycleScope.launch { prefs.setSelectedAppPackages(updated) }

                            if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST && updated.isEmpty()) {
                                if (RRVpnService.isRunning.value || RRVpnService.isStarting.value) sendStopVpn()
                                pendingReconnectAfterSelection = true
                                toast("仅选中代理已没有应用，VPN 已断开；重新选择应用后会自动恢复")
                            } else {
                                val reconnect = pendingReconnectAfterSelection
                                if (updated.isNotEmpty()) pendingReconnectAfterSelection = false
                                scheduleRoutingApply(
                                    perAppMode,
                                    updated,
                                    smartRouting,
                                    reconnectWhenStopped = reconnect
                                )
                            }
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
                        backgroundProtected = backgroundProtected,
                        ruleSetLastUpdated = ruleSetLastUpdated,
                        ruleSetUpdating = updatingRuleSets,
                        pinEnabled = pinEnabled,
                        checkingAppUpdate = checkingAppUpdate,
                        onSmartRoutingChanged = { enabled ->
                            smartRouting = enabled
                            lifecycleScope.launch { prefs.setSmartRouting(enabled) }
                            scheduleRoutingApply(perAppMode, selectedAppPackages, enabled)
                        },
                        onRequestBackgroundProtection = { requestBackgroundProtection() },
                        onUpdateRuleSets = {
                            if (!updatingRuleSets) {
                                updatingRuleSets = true
                                lifecycleScope.launch {
                                    val result = ChinaRuleSetManager.update(this@MainActivity)
                                    updatingRuleSets = false
                                    result.onSuccess { update ->
                                        prefs.setChinaRuleSetLastUpdated(update.updatedAtMillis)
                                        toast("中国规则更新成功，共 ${update.totalBytes / 1024} KB")
                                        scheduleRoutingApply(perAppMode, selectedAppPackages, smartRouting)
                                    }.onFailure { error ->
                                        toast("规则更新失败，已保留旧规则：${error.message ?: "网络错误"}")
                                    }
                                }
                            }
                        },
                        onEnablePin = { showPinSetup = true },
                        onDisablePin = {
                            lifecycleScope.launch {
                                prefs.disablePinLock()
                                pinEnabledCached = false
                                appUnlocked.value = true
                                toast("软件 PIN 锁已关闭")
                            }
                        },
                        onChangePin = { showPinSetup = true },
                        onCheckAppUpdate = {
                            if (!checkingAppUpdate) {
                                checkingAppUpdate = true
                                lifecycleScope.launch {
                                    val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME)
                                    checkingAppUpdate = false
                                    result.onSuccess { update ->
                                        if (update.updateAvailable) {
                                            AlertDialog.Builder(this@MainActivity)
                                                .setTitle("发现 RRBOX 新版本 ${update.latestVersion}")
                                                .setMessage(update.releaseName)
                                                .setPositiveButton("下载更新") { _, _ ->
                                                    runCatching {
                                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                                                    }.onFailure { error ->
                                                        toast("无法打开下载地址：${error.message ?: "未知错误"}")
                                                    }
                                                }
                                                .setNegativeButton("稍后", null)
                                                .show()
                                        } else {
                                            toast("当前已是最新版：${BuildConfig.VERSION_NAME}")
                                        }
                                    }.onFailure { error ->
                                        toast("检查更新失败：${error.message ?: "网络错误"}")
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        editingNode?.let { original ->
            NodeEditDialog(
                node = original,
                onDismiss = { editingNode = null },
                onSave = { edited ->
                    val patched = NodeOverridePatcher.apply(original, edited)
                    lifecycleScope.launch {
                        prefs.setNodeOverride(patched)
                        editingNode = null
                        toast(
                            if (isVpnRunning || isVpnStarting) {
                                "节点已保存；当前连接仍使用旧参数，断开后重新连接即可生效"
                            } else {
                                "节点参数已保存"
                            }
                        )
                    }
                }
            )
        }

        if (showPinSetup) {
            PinSetupDialog(
                onDismiss = { showPinSetup = false },
                onSave = { pin ->
                    lifecycleScope.launch {
                        val credential = withContext(Dispatchers.Default) {
                            PinSecurity.createCredential(pin)
                        }
                        prefs.savePinCredential(credential.saltBase64, credential.hashBase64)
                        pinEnabledCached = true
                        appUnlocked.value = true
                        showPinSetup = false
                        toast("RRBOX PIN 已保存")
                    }
                }
            )
        }
    }

    private suspend fun buildRuntimeConfig(
        targetNode: ProxyNode,
        allNodes: List<ProxyNode>,
        apps: List<AppRouteConfig>,
        smartRouting: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ruleSets = if (smartRouting) {
                ChinaRuleSetManager.ensureBundled(this@MainActivity).getOrNull()
            } else {
                null
            }
            val configJson = ConfigBuilder.buildSingBoxConfig(
                selectedNode = targetNode,
                allNodes = allNodes,
                appRoutes = apps,
                smartRouting = smartRouting,
                ruleSets = ruleSets
            )
            Libbox.checkConfig(configJson)
            configJson
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestBackgroundProtectionGuideIfNeeded()
        }
    }

    private fun requestBackgroundProtectionGuideIfNeeded() {
        lifecycleScope.launch {
            val prefs = RRApplication.instance.preferencesManager
            val shown = runCatching { prefs.backgroundGuideShown.first() }.getOrDefault(false)
            if (shown) return@launch
            prefs.setBackgroundGuideShown(true)
            if (isIgnoringBatteryOptimizations()) {
                updateBackgroundProtectionState()
                return@launch
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("允许 RRBOX 后台持续运行")
                .setMessage("为了减少息屏、锁屏或长时间后台时 VPN 被系统停止，建议允许 RRBOX 不受 Android 电池优化限制。此设置不是 Root 权限，可稍后在设置页重新授权。")
                .setPositiveButton("去授权") { _, _ -> requestBackgroundProtection() }
                .setNegativeButton("稍后") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun requestBackgroundProtection() {
        if (isIgnoringBatteryOptimizations()) {
            updateBackgroundProtectionState()
            Toast.makeText(this, "RRBOX 已不受电池优化限制", Toast.LENGTH_SHORT).show()
            return
        }

        val directRequest = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(directRequest) }
            .onFailure {
                runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { error ->
                        Toast.makeText(
                            this,
                            "无法打开电池优化设置：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBackgroundProtectionState() {
        backgroundOptimizationExempt.value = isIgnoringBatteryOptimizations()
    }

    private fun startVpnWithPermissionCheck(
        configJson: String,
        nodeTag: String,
        nodeId: String,
        perAppMode: String,
        selectedPackages: Set<String>
    ) {
        pendingConfigJson = configJson
        pendingNodeTag = nodeTag
        pendingNodeId = nodeId
        pendingPerAppMode = perAppMode
        pendingSelectedPackages = selectedPackages

        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startVpnServiceInternal()
    }

    private fun startVpnServiceInternal() {
        val config = pendingConfigJson ?: return
        val tag = pendingNodeTag ?: "Node"
        val id = pendingNodeId ?: ""

        val serviceIntent = vpnIntent(
            action = null,
            config = config,
            nodeTag = tag,
            nodeId = id,
            perAppMode = pendingPerAppMode,
            selectedPackages = pendingSelectedPackages
        )

        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
        }.onFailure { error ->
            Toast.makeText(
                this,
                "无法启动 VPN 服务：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
        clearPendingVpn()
    }

    private fun sendRestartVpn(
        config: String,
        nodeTag: String,
        nodeId: String,
        perAppMode: String,
        selectedPackages: Set<String>
    ) {
        val intent = vpnIntent(
            action = RRNotificationManager.ACTION_RESTART_VPN,
            config = config,
            nodeTag = nodeTag,
            nodeId = nodeId,
            perAppMode = perAppMode,
            selectedPackages = selectedPackages
        )
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendStopVpn() {
        startService(Intent(this, RRVpnService::class.java).apply {
            action = RRNotificationManager.ACTION_STOP_VPN
        })
    }

    private fun vpnIntent(
        action: String?,
        config: String,
        nodeTag: String,
        nodeId: String,
        perAppMode: String,
        selectedPackages: Set<String>
    ): Intent = Intent(this, RRVpnService::class.java).apply {
        this.action = action
        putExtra(RRVpnService.EXTRA_CONFIG_JSON, config)
        putExtra(RRVpnService.EXTRA_NODE_TAG, nodeTag)
        putExtra(RRVpnService.EXTRA_NODE_ID, nodeId)
        putExtra(RRVpnService.EXTRA_PER_APP_MODE, perAppMode)
        putStringArrayListExtra(
            RRVpnService.EXTRA_SELECTED_PACKAGES,
            ArrayList(selectedPackages.filter(String::isNotBlank).sorted())
        )
    }

    private fun clearPendingVpn() {
        pendingConfigJson = null
        pendingNodeTag = null
        pendingNodeId = null
        pendingPerAppMode = PerAppPolicyResolver.MODE_ALL
        pendingSelectedPackages = emptySet()
    }
}

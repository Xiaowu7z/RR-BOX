package com.rr.client

import android.Manifest
import android.app.ActivityManager
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rr.client.storage.PreferencesManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val backgroundOptimizationExempt = MutableStateFlow(false)
    private val appUnlocked = MutableStateFlow(false)
    private var pinEnabledCached = false
    private var suppressNextBackgroundLock = false
    private var routingRestartJob: Job? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "通知权限已开启：连接后会显示实时速度"
            else "通知权限未开启：VPN 仍可使用，但通知栏不会显示实时速度",
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateBackgroundProtectionState()

        // Resolve the lock before Compose gets a chance to render app content.
        val prefs = RRApplication.instance.preferencesManager
        pinEnabledCached = runBlocking(Dispatchers.IO) {
            runCatching { prefs.pinEnabled.first() }.getOrDefault(false)
        }
        appUnlocked.value = !pinEnabledCached

        setContent {
            RRClientTheme {
                SecurityGate()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundProtectionState()
    }

    override fun onStop() {
        super.onStop()
        if (suppressNextBackgroundLock) {
            suppressNextBackgroundLock = false
            return
        }
        if (pinEnabledCached && !isChangingConfigurations) {
            appUnlocked.value = false
        }
    }

    @Composable
    private fun SecurityGate() {
        val prefs = RRApplication.instance.preferencesManager
        val pinEnabled by prefs.pinEnabled.collectAsState(initial = pinEnabledCached)
        val unlocked by appUnlocked.collectAsState()
        var verifying by remember { mutableStateOf(false) }
        var unlockError by remember { mutableStateOf<String?>(null) }

        pinEnabledCached = pinEnabled

        when {
            !pinEnabled -> MainApp()
            unlocked -> MainApp()
            else -> PinUnlockScreen(
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

                        if (valid) {
                            prefs.resetPinFailures()
                            unlockError = null
                            verifying = false
                            appUnlocked.value = true
                        } else {
                            val (attempts, maxAttempts) = prefs.recordPinFailure()
                            verifying = false
                            if (attempts >= maxAttempts) {
                                unlockError = "PIN 错误次数已达到 $maxAttempts 次，正在清除 RRBOX 内部数据"
                                delay(350L)
                                clearOwnApplicationData()
                            } else {
                                unlockError = "PIN 不正确，还可尝试 ${maxAttempts - attempts} 次"
                            }
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun MainApp() {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val isVpnRunning by RRVpnService.isRunning.collectAsState()
        val isVpnStarting by RRVpnService.isStarting.collectAsState()
        val lastVpnError by RRVpnService.lastError.collectAsState()
        val currentSpeed by RRVpnService.currentSpeed.collectAsState()
        val sessionTraffic by RRVpnService.sessionTraffic.collectAsState()
        val backgroundProtected by backgroundOptimizationExempt.collectAsState()

        val db = RRApplication.instance.database
        val prefs = RRApplication.instance.preferencesManager
        val nodeOverrides by prefs.nodeOverrides.collectAsState(initial = emptyMap())
        val pinEnabled by prefs.pinEnabled.collectAsState(initial = pinEnabledCached)
        val pinMaxFailedAttempts by prefs.pinMaxFailedAttempts.collectAsState(
            initial = PreferencesManager.DEFAULT_PIN_MAX_FAILED_ATTEMPTS
        )
        val ruleSetLastUpdated by prefs.chinaRuleSetLastUpdated.collectAsState(initial = 0L)

        var subProfiles by remember { mutableStateOf<List<SubProfile>>(emptyList()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        var smartRouting by remember { mutableStateOf(true) }
        var perAppMode by remember { mutableStateOf(PerAppPolicyResolver.MODE_ALL) }
        var proxySelectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var bypassSelectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var addingProfile by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }
        var latencyStates by remember { mutableStateOf<Map<String, NodeLatencyState>>(emptyMap()) }
        var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
        var applyingRouting by remember { mutableStateOf(false) }
        var updatingRuleSets by remember { mutableStateOf(false) }
        var checkingAppUpdate by remember { mutableStateOf(false) }
        var showPinSetup by remember { mutableStateOf(false) }

        val baseNodes = remember(subProfiles) { subProfiles.flatMap { it.nodes } }
        val allNodes = remember(baseNodes, nodeOverrides) {
            baseNodes.map { base -> nodeOverrides[base.id] ?: base }
        }
        val selectedNode = allNodes.find { it.id == selectedNodeId }
        val selectedProfile = subProfiles.firstOrNull { profile ->
            profile.nodes.any { it.id == selectedNodeId }
        }

        fun packagesFor(mode: String): Set<String> = when (mode) {
            PerAppPolicyResolver.MODE_ALLOW_LIST -> proxySelectedPackages
            PerAppPolicyResolver.MODE_DISALLOW_LIST -> bypassSelectedPackages
            else -> emptySet()
        }

        LaunchedEffect(lastVpnError) {
            val message = lastVpnError?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            Toast.makeText(this@MainActivity, "连接失败：$message", Toast.LENGTH_LONG).show()
            RRVpnService.clearLastError()
        }

        LaunchedEffect(Unit) {
            requestNotificationPermissionIfNeeded()

            val loadedProfiles = withContext(Dispatchers.IO) {
                db.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
            }
            subProfiles = loadedProfiles

            val storedId = runCatching { prefs.selectedNodeId.first() }.getOrNull()
            smartRouting = runCatching { prefs.smartRouting.first() }.getOrDefault(true)
            perAppMode = runCatching { prefs.perAppMode.first() }
                .getOrDefault(PerAppPolicyResolver.MODE_ALL)
            proxySelectedPackages = runCatching { prefs.proxySelectedAppPackages.first() }.getOrDefault(emptySet())
            bypassSelectedPackages = runCatching { prefs.bypassSelectedAppPackages.first() }.getOrDefault(emptySet())

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

        fun scheduleRoutingRestart(mode: String, packages: Set<String>, smart: Boolean) {
            if (!RRVpnService.isRunning.value && !RRVpnService.isStarting.value) return
            val node = currentTargetNode() ?: return

            routingRestartJob?.cancel()
            routingRestartJob = lifecycleScope.launch {
                applyingRouting = true
                delay(300L)

                if (mode == PerAppPolicyResolver.MODE_ALLOW_LIST && packages.isEmpty()) {
                    sendStopVpn()
                    applyingRouting = false
                    toast("仅选中代理模式至少需要选择 1 个应用，VPN 已断开")
                    return@launch
                }

                buildRuntimeConfig(node, allNodes, apps, smart, mode, packages)
                    .onSuccess { config ->
                        sendRestartVpn(config, node.tag, node.id)
                    }
                    .onFailure { error ->
                        toast("分流配置失败：${error.message ?: error.javaClass.simpleName}")
                    }

                delay(400L)
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
                }.onFailure { error ->
                    toast("添加订阅失败：${error.message ?: "网络错误"}")
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
                }.onFailure { error ->
                    toast("「${existing.name}」更新失败：${error.message ?: "网络错误"}")
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
                    batch.map { node -> async { node.id to NodeLatencyTester.ping(node.server) } }
                        .awaitAll()
                        .forEach { (id, result) -> latencyStates = latencyStates + (id to result) }
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

                                    val activePackages = packagesFor(perAppMode)
                                    if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST && activePackages.isEmpty()) {
                                        toast("当前是「仅选中代理」模式，请先到「分流」页至少选择 1 个应用")
                                        selectedTab = 2
                                        return@onToggle
                                    }
                                    if (selectedNode == null) selectNode(targetNode.id)

                                    lifecycleScope.launch {
                                        buildRuntimeConfig(
                                            targetNode,
                                            allNodes,
                                            apps,
                                            smartRouting,
                                            perAppMode,
                                            activePackages
                                        ).onSuccess { configJson ->
                                            startVpnWithPermissionCheck(configJson, targetNode.tag, targetNode.id)
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

                    2 -> {
                        val activePackages = packagesFor(perAppMode)
                        AppRoutingScreen(
                            apps = apps,
                            perAppMode = perAppMode,
                            selectedPackages = activePackages,
                            applyingRouting = applyingRouting,
                            onModeChanged = { mode ->
                                if (mode == perAppMode) return@AppRoutingScreen
                                perAppMode = mode
                                lifecycleScope.launch { prefs.setPerAppMode(mode) }
                                scheduleRoutingRestart(mode, packagesFor(mode), smartRouting)
                            },
                            onAppSelectionChanged = { packageName, selected ->
                                when (perAppMode) {
                                    PerAppPolicyResolver.MODE_ALLOW_LIST -> {
                                        val updated = proxySelectedPackages.toMutableSet().apply {
                                            if (selected) add(packageName) else remove(packageName)
                                        }.toSet()
                                        proxySelectedPackages = updated
                                        lifecycleScope.launch { prefs.setProxySelectedAppPackages(updated) }
                                        scheduleRoutingRestart(perAppMode, updated, smartRouting)
                                    }
                                    PerAppPolicyResolver.MODE_DISALLOW_LIST -> {
                                        val updated = bypassSelectedPackages.toMutableSet().apply {
                                            if (selected) add(packageName) else remove(packageName)
                                        }.toSet()
                                        bypassSelectedPackages = updated
                                        lifecycleScope.launch { prefs.setBypassSelectedAppPackages(updated) }
                                        scheduleRoutingRestart(perAppMode, updated, smartRouting)
                                    }
                                }
                            }
                        )
                    }

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
                        pinMaxFailedAttempts = pinMaxFailedAttempts,
                        checkingAppUpdate = checkingAppUpdate,
                        onSmartRoutingChanged = { enabled ->
                            smartRouting = enabled
                            lifecycleScope.launch { prefs.setSmartRouting(enabled) }
                            scheduleRoutingRestart(perAppMode, packagesFor(perAppMode), enabled)
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
                                        scheduleRoutingRestart(perAppMode, packagesFor(perAppMode), smartRouting)
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
                        onPinMaxFailedAttemptsChanged = { value ->
                            lifecycleScope.launch {
                                runCatching { prefs.setPinMaxFailedAttempts(value) }
                                    .onFailure { toast(it.message ?: "无法保存错误次数") }
                            }
                        },
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
                                                    suppressNextBackgroundLock = true
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
        smartRouting: Boolean,
        perAppMode: String,
        selectedPackages: Set<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ruleSets = if (smartRouting) {
                ChinaRuleSetManager.ensureBundled(this@MainActivity).getOrNull()
            } else null

            val configJson = ConfigBuilder.buildSingBoxConfig(
                selectedNode = targetNode,
                allNodes = allNodes,
                appRoutes = apps,
                smartRouting = smartRouting,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
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

        suppressNextBackgroundLock = true
        val directRequest = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(directRequest) }
            .onFailure {
                runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    .onFailure { error ->
                        suppressNextBackgroundLock = false
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

    private fun startVpnWithPermissionCheck(configJson: String, nodeTag: String, nodeId: String) {
        pendingConfigJson = configJson
        pendingNodeTag = nodeTag
        pendingNodeId = nodeId

        val intent = VpnService.prepare(this)
        if (intent != null) vpnLauncher.launch(intent) else startVpnServiceInternal()
    }

    private fun startVpnServiceInternal() {
        val config = pendingConfigJson ?: return
        val tag = pendingNodeTag ?: "Node"
        val id = pendingNodeId ?: ""

        val serviceIntent = vpnIntent(null, config, tag, id)
        runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
            .onFailure { error ->
                Toast.makeText(
                    this,
                    "无法启动 VPN 服务：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        clearPendingVpn()
    }

    private fun sendRestartVpn(config: String, nodeTag: String, nodeId: String) {
        ContextCompat.startForegroundService(
            this,
            vpnIntent(RRNotificationManager.ACTION_RESTART_VPN, config, nodeTag, nodeId)
        )
    }

    private fun sendStopVpn() {
        startService(Intent(this, RRVpnService::class.java).apply {
            action = RRNotificationManager.ACTION_STOP_VPN
        })
    }

    private fun vpnIntent(action: String?, config: String, nodeTag: String, nodeId: String): Intent =
        Intent(this, RRVpnService::class.java).apply {
            this.action = action
            putExtra(RRVpnService.EXTRA_CONFIG_JSON, config)
            putExtra(RRVpnService.EXTRA_NODE_TAG, nodeTag)
            putExtra(RRVpnService.EXTRA_NODE_ID, nodeId)
        }

    private fun clearPendingVpn() {
        pendingConfigJson = null
        pendingNodeTag = null
        pendingNodeId = null
    }

    private fun clearOwnApplicationData() {
        runCatching {
            val manager = getSystemService(ActivityManager::class.java)
                ?: error("ActivityManager unavailable")
            check(manager.clearApplicationUserData()) { "Android 拒绝清除应用数据请求" }
        }.onFailure { error ->
            Toast.makeText(
                this,
                "无法自动清除 RRBOX 数据：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

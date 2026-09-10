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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rr.client.core.ConfigBuilder
import kotlinx.coroutines.CancellationException
import com.rr.client.subscription.ImportLimits
import com.rr.client.subscription.SubscriptionNodeReconciler
import com.rr.client.storage.LocalProfileStore
import com.rr.client.core.NodeIdentity
import com.rr.client.core.LocalNodeDeletionPolicy
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.NodeLatencyTester
import com.rr.client.core.NodeOverridePatcher
import com.rr.client.core.model.AppRouteConfig
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.AppManager
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.routing.AutoProxySelectionPolicy
import com.rr.client.security.PinSecurity
import com.rr.client.storage.PreferencesManager
import com.rr.client.subscription.SubscriptionFetcher
import com.rr.client.subscription.SubscriptionParser
import com.rr.client.subscription.SubscriptionUrlNormalizer
import com.rr.client.subscription.TrafficInfoNode
import com.rr.client.subscription.model.SubProfile
import com.rr.client.ui.components.NodeEditDialog
import com.rr.client.ui.components.PinSetupDialog
import com.rr.client.ui.components.PinUnlockScreen
import com.rr.client.ui.screens.AppRoutingScreen
import com.rr.client.ui.screens.DashboardScreen
import com.rr.client.ui.screens.NodeGroupUi
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var routingRestartGeneration = 0L
    private var clearingApplicationData = false

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
        suppressNextBackgroundLock = false
        lifecycleScope.launch {
            // The selected mode may have changed while Android's permission activity was open.
            if (rootModeSelectedOrActive() || result.resultCode == RESULT_OK) {
                startVpnServiceInternal()
            } else {
                clearPendingVpn()
                Toast.makeText(this@MainActivity, "VPN 授权未通过", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var pendingConfigJson: String? = null
    private var pendingNodeTag: String? = null
    private var pendingNodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.rr.client.sharing.ShareDocumentExporter.install(this)
        updateBackgroundProtectionState()

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
        if (pinEnabledCached && !isChangingConfigurations) appUnlocked.value = false
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
                        val valid = withContext(Dispatchers.Default) { PinSecurity.verify(pin, salt, hash) }

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
        val routingUiScope = rememberCoroutineScope()
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        val isVpnRunning by RRVpnService.isRunning.collectAsState()
        val isVpnStarting by RRVpnService.isStarting.collectAsState()
        val activeRuntimeNodeId by RRVpnService.activeRuntimeNodeId.collectAsState()
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
        val ruleUpdateStatus by ChinaRuleSetManager.status.collectAsState()

        var subProfiles by remember { mutableStateOf<List<SubProfile>>(emptyList()) }
        var selectedNodeId by remember { mutableStateOf<String?>(null) }
        var smartRouting by remember { mutableStateOf(true) }
        var fastForwarding by remember { mutableStateOf(false) }
        var perAppMode by remember { mutableStateOf(PerAppPolicyResolver.MODE_ALL) }
        var proxySelectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var autoProxyExcludedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var bypassSelectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
        var refreshingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var addingProfile by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf<List<AppRouteConfig>>(emptyList()) }
        var routingSelectionReady by remember { mutableStateOf(false) }
        var selectingAutomatically by remember { mutableStateOf(false) }
        var latencyStates by remember { mutableStateOf<Map<String, NodeLatencyState>>(emptyMap()) }
        var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
        var applyingRouting by remember { mutableStateOf(false) }
        var updatingRuleSets by remember { mutableStateOf(false) }
        var checkingAppUpdate by remember { mutableStateOf(false) }
        var showPinSetup by remember { mutableStateOf(false) }

        val baseNodes = remember(subProfiles) { subProfiles.flatMap { it.nodes } }
        val allNodes = remember(baseNodes, nodeOverrides) {
            baseNodes.map { base -> NodeOverridePatcher.resolve(base, nodeOverrides[base.id]) }
        }
        val selectableNodes = remember(allNodes) { allNodes.filterNot(TrafficInfoNode::isInfoNode) }
        val latestAllNodes by rememberUpdatedState(allNodes)
        val subscriptionProfiles = remember(subProfiles) { subProfiles.filterNot { it.isLocal } }
        val nodeGroups = remember(subProfiles, allNodes) {
            val resolved = allNodes.associateBy { it.id }
            val local = subProfiles.firstOrNull { it.isLocal }
            buildList {
                add(
                    NodeGroupUi(
                        id = SubProfile.LOCAL_PROFILE_ID,
                        name = SubProfile.LOCAL_PROFILE_NAME,
                        nodes = local?.nodes.orEmpty().mapNotNull { resolved[it.id] },
                        isLocal = true
                    )
                )
                subscriptionProfiles.forEach { profile ->
                    add(
                        NodeGroupUi(
                            id = profile.id,
                            name = profile.name,
                            nodes = profile.nodes.mapNotNull { resolved[it.id] },
                            isLocal = false,
                            subscriptionUrl = profile.url
                        )
                    )
                }
            }
        }
        val selectedNode = selectableNodes.find { it.id == selectedNodeId }
        val selectedProfile = subProfiles.firstOrNull { profile -> profile.nodes.any { it.id == selectedNodeId } }

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

        fun refreshFromProfiles(updated: List<SubProfile>) {
            subProfiles = updated
            val nodesNow = updated.flatMap { it.nodes }
                .map { NodeOverridePatcher.resolve(it, nodeOverrides[it.id]) }
                .filterNot(TrafficInfoNode::isInfoNode)
            val current = selectedNodeId
            val resolved = if (nodesNow.any { it.id == current }) current else nodesNow.firstOrNull()?.id
            if (resolved != current) {
                selectedNodeId = resolved
                if (resolved != null) lifecycleScope.launch { prefs.setSelectedNodeId(resolved) }
            }
            latencyStates = latencyStates.filterKeys { id -> nodesNow.any { it.id == id } }
        }

        LaunchedEffect(Unit) {
            requestNotificationPermissionIfNeeded()

            val loadedProfiles = withContext(Dispatchers.IO) {
                db.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
            }
            subProfiles = loadedProfiles

            val storedId = runCatching { prefs.selectedNodeId.first() }.getOrNull()
            smartRouting = runCatching { prefs.smartRouting.first() }.getOrDefault(true)
            fastForwarding = runCatching { prefs.fastForwarding.first() }.getOrDefault(false)
            perAppMode = runCatching { prefs.perAppMode.first() }.getOrDefault(PerAppPolicyResolver.MODE_ALL)
            proxySelectedPackages = runCatching { prefs.proxySelectedAppPackages.first() }.getOrDefault(emptySet())
            autoProxyExcludedPackages = runCatching { prefs.autoProxyExcludedPackages.first() }.getOrDefault(emptySet())
            bypassSelectedPackages = runCatching { prefs.bypassSelectedAppPackages.first() }.getOrDefault(emptySet())

            val nodesNow = loadedProfiles.flatMap { it.nodes }
                .map { NodeOverridePatcher.resolve(it, nodeOverrides[it.id]) }
                .filterNot(TrafficInfoNode::isInfoNode)
            val resolved = if (nodesNow.any { it.id == storedId }) storedId else nodesNow.firstOrNull()?.id
            selectedNodeId = resolved
            if (resolved != null) prefs.setSelectedNodeId(resolved)

            val appMgr = AppManager(this@MainActivity)
            apps = withContext(Dispatchers.IO) {
                val groups = ChinaRuleSetManager.ensureBundled(this@MainActivity).getOrNull()
                    ?.policy?.proxyPackageGroups.orEmpty()
                appMgr.getInstalledApps(includeSystem = true, visiblePackages = proxySelectedPackages + bypassSelectedPackages +
                    AutoProxySelectionPolicy.recommendedPackages(groups))
            }
            routingSelectionReady = true
            db.profileDao().observeProfiles().collect { entities ->
                refreshFromProfiles(entities.map { SubProfile.fromEntity(it) })
            }
        }

        fun toast(text: String) = Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
        fun currentTargetNode(): ProxyNode? = selectedNode ?: selectableNodes.firstOrNull()

        fun persistLocalNodes(transform: (List<ProxyNode>) -> List<ProxyNode>, message: String? = null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { LocalProfileStore.update(db, transform) }
                    // Room's observation refreshes every screen from committed data.
                    message?.let(::toast)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    toast("本地节点保存失败，请重试")
                }
            }
        }

        fun importLocalNodes(raw: String) {
            if (raw.isBlank()) {
                toast("没有读取到可导入的节点内容")
                return
            }
            lifecycleScope.launch {
                val parsed = withContext(Dispatchers.Default) {
                    runCatching {
                        SubscriptionParser.parseContent(raw, SubProfile.LOCAL_PROFILE_ID, SubProfile.LOCAL_PROFILE_NAME)
                    }.getOrDefault(emptyList())
                }
                if (parsed.size > ImportLimits.MAX_NODES) {
                    toast("一次最多导入 ${ImportLimits.MAX_NODES} 个节点")
                    return@launch
                }
                if (parsed.isEmpty()) {
                    toast("没有识别到支持的节点；可粘贴分享链接、sing-box JSON 或 Clash YAML")
                    return@launch
                }

                val valid = withContext(Dispatchers.IO) {
                    parsed.mapNotNull { candidate ->
                        val localNode = candidate.copy(
                            id = "local-${UUID.randomUUID()}",
                            profileId = SubProfile.LOCAL_PROFILE_ID,
                            profileName = SubProfile.LOCAL_PROFILE_NAME
                        )
                        runCatching {
                            val check = ConfigBuilder.buildSingBoxConfig(
                                selectedNode = localNode,
                                allNodes = listOf(localNode),
                                appRoutes = emptyList(),
                                smartRouting = false,
                                perAppMode = PerAppPolicyResolver.MODE_ALL,
                                fastForwarding = false
                            )
                            Libbox.checkConfig(check)
                            localNode
                        }.getOrNull()
                    }
                }
                if (valid.isEmpty()) {
                    toast("节点格式已识别，但 sing-box 1.14 校验未通过，未写入本地节点")
                    return@launch
                }

                persistLocalNodes({ latest ->
                    val keys = latest.map(NodeIdentity::key).toMutableSet()
                    latest + valid.filter { keys.add(NodeIdentity.key(it)) }
                }, "导入完成，重复节点已自动跳过")
            }
        }

        fun deleteLocalNode(node: ProxyNode) {
            if (!node.profileId.equals(SubProfile.LOCAL_PROFILE_ID)) return
            val vpnBusy = isVpnRunning || isVpnStarting
            if (!LocalNodeDeletionPolicy.canDelete(node.id, activeRuntimeNodeId, vpnBusy)) {
                toast(
                    if (node.id == activeRuntimeNodeId) {
                        "当前节点正在使用，请先断开或切换连接后再删除"
                    } else {
                        "正在确认当前运行节点，请稍后重试"
                    }
                )
                return
            }
            lifecycleScope.launch {
                prefs.clearNodeOverride(node.id)
                persistLocalNodes({ latest -> latest.filterNot { it.id == node.id } }, "已删除本地节点「${node.tag}」")
            }
        }


        fun renameNode(node: ProxyNode, requestedName: String) {
            val newName = requestedName.trim()
            if (newName.isEmpty()) {
                toast("节点名称不能为空")
                return
            }
            if (newName == node.tag) return
            if (node.profileId == SubProfile.LOCAL_PROFILE_ID) {
                persistLocalNodes({ latest ->
                    require(latest.any { it.id == node.id }) { "节点已被移除" }
                    latest.map {
                        if (it.id == node.id) NodeOverridePatcher.apply(it, it.copy(tag = newName)) else it
                    }
                }, "已重命名为「$newName」")
            } else {
                lifecycleScope.launch {
                    val base = baseNodes.firstOrNull { it.id == node.id } ?: return@launch
                    val override = prefs.nodeOverrides.first()[node.id]
                    try {
                        prefs.setNodeOverride(NodeOverridePatcher.renameOverride(base, override, newName))
                        toast("已重命名为「$newName」")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        toast("重命名保存失败，请重试")
                    }
                }
            }
        }

        fun scheduleRoutingRestart(
            mode: String,
            packages: Set<String>,
            smart: Boolean,
            fast: Boolean = fastForwarding,
            ruleUpdate: ChinaRuleSetManager.UpdateResult? = null
        ) {
            if ((!RRVpnService.isRunning.value && !RRVpnService.isStarting.value) ||
                (ruleUpdate != null && RRVpnService.isStarting.value)) {
                ruleUpdate?.let { ChinaRuleSetManager.noteActivationFailure(this@MainActivity, it.generation, "请等待连接完成后再更新", it.operation) }
                return
            }
            // Updating rules must preserve the running node, even if another node is selected in UI.
            val activeId = RRVpnService.activeRuntimeNodeId.value
            val nodesSnapshot = latestAllNodes
            val node = nodesSnapshot.firstOrNull { it.id == activeId }
            if (node == null) {
                ruleUpdate?.let { ChinaRuleSetManager.noteActivationFailure(this@MainActivity, it.generation, "当前节点已变化，请重试", it.operation) }
                return
            }
            val runtimeGeneration = RRVpnService.currentRuntimeGeneration()

            routingRestartJob?.cancel()
            val updateGeneration = ++routingRestartGeneration
            routingRestartJob = lifecycleScope.launch {
                applyingRouting = true
                var dispatched = false
                try {
                    delay(300L)
                    if (!com.rr.client.vpn.RoutingUpdatePolicy.mayApply(
                            com.rr.client.vpn.VpnConnectionIntentStore.isDesiredRunning(this@MainActivity),
                            node.id, RRVpnService.activeRuntimeNodeId.value,
                            runtimeGeneration, RRVpnService.currentRuntimeGeneration()
                        )) return@launch

                    if (mode == PerAppPolicyResolver.MODE_ALLOW_LIST && packages.isEmpty()) {
                        sendStopVpn()
                        toast("仅选中应用模式至少需要选择 1 个应用，VPN 已断开")
                        return@launch
                    }

                    val result = buildRuntimeConfig(node, nodesSnapshot, apps, smart, mode, packages, fast, ruleUpdate?.paths)
                    currentCoroutineContext().ensureActive()
                    if (!com.rr.client.vpn.RoutingUpdatePolicy.mayApply(
                            com.rr.client.vpn.VpnConnectionIntentStore.isDesiredRunning(this@MainActivity),
                            node.id, RRVpnService.activeRuntimeNodeId.value,
                            runtimeGeneration, RRVpnService.currentRuntimeGeneration()
                        )) return@launch
                    result.onSuccess { config ->
                        sendRestartVpn(config, node.tag, node.id, runtimeGeneration, ruleUpdate)
                        dispatched = true
                    }.onFailure { error ->
                        toast("分流配置失败：${error.message ?: error.javaClass.simpleName}")
                    }
                    delay(400L)
                } finally {
                    if (!dispatched && ruleUpdate != null) {
                        ChinaRuleSetManager.noteActivationFailure(this@MainActivity, ruleUpdate.generation, "连接或设置发生变化，继续使用原有规则", ruleUpdate.operation)
                    }
                    if (updateGeneration == routingRestartGeneration) applyingRouting = false
                }
            }
        }

        fun addProfile(name: String, url: String) {
            if (addingProfile) {
                toast("订阅正在同步，请稍候")
                return
            }
            val trimmedUrl = SubscriptionUrlNormalizer.clean(url)
            val candidates = runCatching { SubscriptionUrlNormalizer.candidates(trimmedUrl) }.getOrNull()
            if (candidates == null) {
                toast("请填写有效的 HTTP/HTTPS 订阅地址")
                return
            }
            if (subscriptionProfiles.any { it.url == trimmedUrl }) {
                toast("该订阅已存在，请直接点击更新")
                return
            }
            addingProfile = true
            val profileId = UUID.randomUUID().toString()
            val profileName = name.trim().ifEmpty {
                "订阅 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
            }
            lifecycleScope.launch {
                try {
                    val (newNodes, userInfo) = SubscriptionFetcher().fetchSubscription(trimmedUrl, profileId, profileName).getOrThrow()
                    val profile = SubProfile(profileId, profileName, trimmedUrl, System.currentTimeMillis(), newNodes, userInfo)
                    withContext(Dispatchers.IO) { db.profileDao().insertProfile(profile.toEntity()) }
                    toast("「$profileName」同步成功：${newNodes.size} 个节点")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    toast("添加订阅失败：${com.rr.client.lab.RRLogStore.redact(error.message ?: "网络或存储错误")}")
                } finally {
                    addingProfile = false
                }
            }
        }

        fun importClipboardContent(raw: String) {
            val trimmed = SubscriptionUrlNormalizer.clean(raw)
            fun importSubscription() {
                selectedTab = 3
                addProfile("", trimmed)
            }
            when {
                SubscriptionUrlNormalizer.looksLikeSubscriptionAddress(trimmed) -> importSubscription()
                SubscriptionUrlNormalizer.isAmbiguousHttpAddress(trimmed) -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("识别到 HTTP/HTTPS 地址")
                        .setMessage("此地址可能是订阅，也可能是 HTTP 代理节点，请选择导入方式。")
                        .setPositiveButton("作为订阅") { _, _ -> importSubscription() }
                        .setNegativeButton("作为节点") { _, _ ->
                            importLocalNodes(SubscriptionUrlNormalizer.candidates(trimmed).first())
                        }
                        .setNeutralButton("取消", null)
                        .show()
                }
                else -> importLocalNodes(trimmed)
            }
        }

        fun renameProfile(profileId: String, requestedName: String) {
            val name = requestedName.trim()
            if (name.isEmpty() || name.length > 80) {
                toast("订阅分组名称请输入 1–80 个字符")
                return
            }
            if (subProfiles.none { it.id == profileId && !it.isLocal }) return
            lifecycleScope.launch {
                try {
                    val changed = withContext(Dispatchers.IO) { db.profileDao().renameProfile(profileId, name) }
                    toast(if (changed > 0) "已重命名为「$name」" else "订阅已被移除")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    toast("分组名称保存失败，请重试")
                }
            }
        }

        fun refreshProfile(profileId: String) {
            val existing = subProfiles.find { it.id == profileId && !it.isLocal } ?: return
            if (profileId in refreshingIds) return
            refreshingIds = refreshingIds + profileId
            lifecycleScope.launch {
                try {
                    prefs.migrateNameOnlyOverrides(existing.nodes)
                    val (newNodes, userInfo) = SubscriptionFetcher().fetchSubscription(existing.url, existing.id, existing.name).getOrThrow()
                    val reconciled = SubscriptionNodeReconciler.reconcile(existing.nodes, newNodes)
                    val updated = existing.copy(lastUpdated = System.currentTimeMillis(), nodes = reconciled, userInfo = userInfo)
                    val entity = updated.toEntity()
                    val changed = withContext(Dispatchers.IO) {
                        // A concurrent rename must survive an in-flight subscription download.
                        // Updating existing content also cannot resurrect a deleted group.
                        db.profileDao().updateSubscriptionContent(
                            id = entity.id,
                            nodesJson = entity.nodesJson,
                            lastUpdated = entity.lastUpdated,
                            uploadBytes = entity.uploadBytes,
                            downloadBytes = entity.downloadBytes,
                            totalBytes = entity.totalBytes,
                            expireTime = entity.expireTime
                        )
                    }
                    if (changed == 0) {
                        toast("订阅已被移除，未保存本次更新")
                        return@launch
                    }
                    val retained = reconciled.mapTo(hashSetOf()) { it.id }
                    existing.nodes.filterNot { it.id in retained }.forEach { prefs.clearNodeOverride(it.id) }
                    toast("「${existing.name}」更新成功：${newNodes.size} 个节点")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    toast("订阅更新未完成，请重试：${com.rr.client.lab.RRLogStore.redact(error.message ?: "网络或存储错误")}")
                } finally {
                    refreshingIds = refreshingIds - profileId
                }
            }
        }

        fun deleteProfile(profileId: String) {
            val existing = subProfiles.find { it.id == profileId && !it.isLocal } ?: return
            if (profileId in refreshingIds) {
                toast("订阅正在更新，请完成后再删除")
                return
            }
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
            if (selectableNodes.none { it.id == nodeId }) return
            selectedNodeId = nodeId
            lifecycleScope.launch { prefs.setSelectedNodeId(nodeId) }
        }

        fun pingNode(node: ProxyNode) {
            if (TrafficInfoNode.isInfoNode(node)) return
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
            if (selectableNodes.isEmpty()) return
            latencyStates = latencyStates + selectableNodes.associate { it.id to NodeLatencyState.Testing }
            lifecycleScope.launch {
                selectableNodes.chunked(3).forEach { batch ->
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
                                        toast("还没有任何节点：可在「节点」页 + 号单独添加，或到「订阅」页添加订阅")
                                        selectedTab = 1
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
                                            activePackages,
                                            fastForwarding
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
                        groups = nodeGroups,
                        selectedNodeId = selectedNodeId,
                        latencyStates = latencyStates,
                        editedNodeIds = nodeOverrides.keys,
                        onSelectNode = { node ->
                            selectNode(node.id)
                            toast("已切换节点：${node.tag}")
                        },
                        onPingAll = ::pingAllNodes,
                        onPingNode = ::pingNode,
                        onRenameNode = ::renameNode,
                        onRenameProfile = ::renameProfile,
                        onEditNode = { node -> editingNode = node },
                        onResetNodeEdit = { node ->
                            lifecycleScope.launch {
                                prefs.clearNodeOverride(node.id)
                                toast("已恢复订阅中的原始节点参数")
                            }
                        },
                        onDeleteLocalNode = ::deleteLocalNode,
                        onImportText = ::importClipboardContent,
                        onImportClipboard = ::importClipboardContent,
                        onCreateManualNode = { protocol ->
                            editingNode = manualNodeTemplate(protocol)
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
                            loadingApps = !routingSelectionReady,
                            selectingAutomatically = selectingAutomatically,
                            onModeChanged = { mode ->
                                if (mode == perAppMode) return@AppRoutingScreen
                                perAppMode = mode
                                lifecycleScope.launch { prefs.setPerAppMode(mode) }
                                scheduleRoutingRestart(mode, packagesFor(mode), smartRouting)
                            },
                            onAutoSelect = {
                                if (perAppMode != PerAppPolicyResolver.MODE_ALLOW_LIST ||
                                    !routingSelectionReady || applyingRouting || selectingAutomatically) return@AppRoutingScreen
                                selectingAutomatically = true
                                routingUiScope.launch {
                                    try {
                                        val (installedApps, groups) = withContext(Dispatchers.IO) {
                                            val policy = ChinaRuleSetManager.ensureBundled(this@MainActivity).getOrThrow().policy
                                            val visible = proxySelectedPackages + bypassSelectedPackages +
                                                AutoProxySelectionPolicy.recommendedPackages(policy.proxyPackageGroups)
                                            AppManager(this@MainActivity).getInstalledApps(includeSystem = true, visiblePackages = visible) to policy.proxyPackageGroups
                                        }
                                        val previous = proxySelectedPackages
                                        val updated = AutoProxySelectionPolicy.select(
                                            installedPackages = installedApps.map { it.packageName }.toSet(),
                                            currentSelection = previous,
                                            excludedPackages = autoProxyExcludedPackages,
                                            extraPackageGroups = groups
                                        )
                                        // Persist the entire batch before one runtime update; never loop through app switches.
                                        prefs.setProxyAppSelection(updated, autoProxyExcludedPackages)
                                        apps = installedApps
                                        proxySelectedPackages = updated
                                        if (updated != previous) {
                                            scheduleRoutingRestart(PerAppPolicyResolver.MODE_ALLOW_LIST, updated, smartRouting)
                                        }
                                        val added = (updated - previous).size
                                        toast(when {
                                            updated.isEmpty() -> "没有可自动选择的应用，请手动勾选需要代理的应用"
                                            added == 0 -> "名单已更新，保留你的手动选择，共 ${updated.size} 个应用"
                                            else -> "自动新增 $added 个应用，共选择 ${updated.size} 个，可继续手动调整"
                                        })
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        toast("自动选择失败，已保留原名单：${error.message ?: error.javaClass.simpleName}")
                                    } finally {
                                        selectingAutomatically = false
                                    }
                                }
                            },
                            onAppSelectionChanged = { packageName, selected ->
                                when (perAppMode) {
                                    PerAppPolicyResolver.MODE_ALLOW_LIST -> {
                                        val updated = proxySelectedPackages.toMutableSet().apply {
                                            if (selected) add(packageName) else remove(packageName)
                                        }.toSet()
                                        val exclusions = autoProxyExcludedPackages.toMutableSet().apply {
                                            if (selected) remove(packageName) else add(packageName)
                                        }.toSet()
                                        proxySelectedPackages = updated
                                        autoProxyExcludedPackages = exclusions
                                        lifecycleScope.launch {
                                            prefs.setProxyAppSelection(updated, exclusions)
                                            if (perAppMode == PerAppPolicyResolver.MODE_ALLOW_LIST && proxySelectedPackages == updated) {
                                                scheduleRoutingRestart(PerAppPolicyResolver.MODE_ALLOW_LIST, updated, smartRouting)
                                            }
                                        }
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
                        profiles = subscriptionProfiles,
                        busyIds = refreshingIds,
                        adding = addingProfile,
                        onAddProfile = { name, url -> addProfile(name, url) },
                        onRefreshProfile = { id -> refreshProfile(id) },
                        onRenameProfile = ::renameProfile,
                        onDeleteProfile = { id -> deleteProfile(id) }
                    )

                    4 -> SettingsScreen(
                        smartRouting = smartRouting,
                        fastForwarding = fastForwarding,
                        backgroundProtected = backgroundProtected,
                        ruleSetLastUpdated = ruleUpdateStatus.savedAtMillis.takeIf { it > 0 } ?: ruleSetLastUpdated,
                        ruleSetUpdating = updatingRuleSets || ruleUpdateStatus.busy,
                        ruleVersion = ruleUpdateStatus.policyVersion,
                        ruleBundleVersion = ruleUpdateStatus.bundleVersion,
                        ruleDescription = ruleUpdateStatus.description,
                        ruleUpdateMessage = ruleUpdateStatus.message,
                        pinEnabled = pinEnabled,
                        pinMaxFailedAttempts = pinMaxFailedAttempts,
                        checkingAppUpdate = checkingAppUpdate,
                        onVpnPermissionPendingChanged = { pending -> suppressNextBackgroundLock = pending },
                        onSmartRoutingChanged = { enabled ->
                            smartRouting = enabled
                            lifecycleScope.launch { prefs.setSmartRouting(enabled) }
                            scheduleRoutingRestart(perAppMode, packagesFor(perAppMode), enabled)
                        },
                        onFastForwardingChanged = { enabled ->
                            fastForwarding = enabled
                            lifecycleScope.launch { prefs.setFastForwarding(enabled) }
                            scheduleRoutingRestart(perAppMode, packagesFor(perAppMode), smartRouting, enabled)
                        },
                        onRequestBackgroundProtection = { requestBackgroundProtection() },
                        onUpdateRuleSets = {
                            if (!updatingRuleSets && !ruleUpdateStatus.busy) {
                                updatingRuleSets = true
                                lifecycleScope.launch {
                                    var candidate: ChinaRuleSetManager.UpdateResult? = null
                                    var handedOff = false
                                    try {
                                        val update = ChinaRuleSetManager.update(this@MainActivity).getOrThrow()
                                        candidate = update
                                        currentCoroutineContext().ensureActive()
                                        val live = RRVpnService.isRunning.value || RRVpnService.isStarting.value
                                        val needsApply = smartRouting && live &&
                                            com.rr.client.core.RuleSetRuntimePolicy.needsReload(
                                                RRVpnService.currentRuntimeConfig(), update.paths.geositeChina, update.paths.geoipChina
                                            )
                                        if (needsApply) {
                                            scheduleRoutingRestart(perAppMode, packagesFor(perAppMode), smartRouting, ruleUpdate = update)
                                            handedOff = true
                                            toast("规则校验完成，正在应用；连接可能短暂重连")
                                        } else {
                                            // This nonsuspending decision + commit cannot interleave a UI start/stop.
                                            check(!RRVpnService.isStarting.value) { "连接正在启动，请稍后重试" }
                                            check(ChinaRuleSetManager.commitPrepared(this@MainActivity, update.generation, update.baseGeneration, update.operation)) {
                                                "规则版本已变化，请重新更新"
                                            }
                                            handedOff = true
                                            prefs.setChinaRuleSetLastUpdated(update.updatedAtMillis)
                                            toast(if (update.changed) "分流规则已保存，下次连接使用新规则" else "分流规则已是最新")
                                        }
                                    } catch (error: Exception) {
                                        if (error is CancellationException) throw error
                                        toast("规则更新未完成，原有规则继续可用：${error.message ?: "网络错误"}")
                                    } finally {
                                        if (!handedOff) candidate?.let {
                                            ChinaRuleSetManager.noteActivationFailure(this@MainActivity, it.generation, "更新未完成或已取消", it.operation)
                                        }
                                        updatingRuleSets = false
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
                                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))) }
                                                        .onFailure { error -> toast("无法打开下载地址：${error.message ?: "未知错误"}") }
                                                }
                                                .setNegativeButton("稍后", null)
                                                .show()
                                        } else {
                                            toast("当前已是最新版：${BuildConfig.VERSION_NAME}")
                                        }
                                    }.onFailure { error -> toast("检查更新失败：${error.message ?: "网络错误"}") }
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
                    val patched = NodeOverridePatcher.apply(original, edited).copy(nameOverrideOnly = false)
                    lifecycleScope.launch {
                        val valid = withContext(Dispatchers.IO) {
                            runCatching {
                                Libbox.checkConfig(ConfigBuilder.buildSingBoxConfig(
                                    patched, listOf(patched), emptyList(), smartRouting = false
                                ))
                            }.isSuccess
                        }
                        if (!valid) {
                            toast("节点未通过 sing-box 配置校验，未保存；请检查参数或使用 Raw 模式")
                            return@launch
                        }
                        try {
                            if (original.profileId == SubProfile.LOCAL_PROFILE_ID) {
                                val normalized = patched.copy(
                                    profileId = SubProfile.LOCAL_PROFILE_ID,
                                    profileName = SubProfile.LOCAL_PROFILE_NAME
                                )
                                withContext(Dispatchers.IO) { LocalProfileStore.update(db) { latest ->
                                    if (latest.any { it.id == original.id }) {
                                        latest.map { if (it.id == original.id) normalized else it }
                                    } else latest + normalized
                                } }
                            } else {
                                prefs.setNodeOverride(patched)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            toast("保存失败，原节点未修改，请重试")
                            return@launch
                        }
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
                        val credential = withContext(Dispatchers.Default) { PinSecurity.createCredential(pin) }
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
        selectedPackages: Set<String>,
        fastForwarding: Boolean,
        preparedRules: ChinaRuleSetManager.Paths? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val ruleSets = if (smartRouting) preparedRules ?: ChinaRuleSetManager.ensureBundled(this@MainActivity).getOrThrow() else null
            val configJson = ConfigBuilder.buildSingBoxConfig(
                selectedNode = targetNode,
                allNodes = allNodes,
                appRoutes = apps,
                smartRouting = smartRouting,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                fastForwarding = fastForwarding,
                ruleSets = ruleSets,
                routingPolicy = ruleSets?.policy ?: com.rr.client.routing.RoutingPolicySnapshot.bundled()
            )
            Libbox.checkConfig(configJson)
            configJson
        }
    }

    private fun manualNodeTemplate(type: ProtocolType): ProxyNode = ProxyNode(
        id = "local-${UUID.randomUUID()}",
        tag = "新节点",
        type = type,
        server = "",
        serverPort = when (type) {
            ProtocolType.SHADOWSOCKS -> 8388
            else -> 443
        },
        network = when (type) {
            ProtocolType.VMESS_WS_ARGO -> "ws"
            else -> "tcp"
        },
        tlsEnabled = type != ProtocolType.SHADOWSOCKS,
        ssMethod = if (type == ProtocolType.SHADOWSOCKS) "aes-128-gcm" else "",
        profileId = SubProfile.LOCAL_PROFILE_ID,
        profileName = SubProfile.LOCAL_PROFILE_NAME
    )

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
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

        lifecycleScope.launch {
            if (rootModeSelectedOrActive()) {
                startVpnServiceInternal()
                return@launch
            }
            val intent = VpnService.prepare(this@MainActivity)
            if (intent != null) {
                suppressNextBackgroundLock = true
                try {
                    vpnLauncher.launch(intent)
                } catch (error: Exception) {
                    suppressNextBackgroundLock = false
                    clearPendingVpn()
                    Toast.makeText(this@MainActivity, "无法打开 VPN 授权：${error.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                startVpnServiceInternal()
            }
        }
    }

    private suspend fun rootModeSelectedOrActive(): Boolean =
        RRApplication.instance.preferencesManager.tunEngine.first() == PreferencesManager.TUN_ENGINE_ROOT ||
            RRVpnService.activeRuntimeEngine.value == PreferencesManager.TUN_ENGINE_ROOT ||
            RRVpnService.hasRootDataPlane()

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

    private fun sendRestartVpn(config: String, nodeTag: String, nodeId: String, runtimeGeneration: Long,
        ruleUpdate: ChinaRuleSetManager.UpdateResult? = null) {
        ContextCompat.startForegroundService(
            this,
            vpnIntent(RRNotificationManager.ACTION_RESTART_VPN, config, nodeTag, nodeId).apply {
                putExtra(RRVpnService.EXTRA_ROUTING_UPDATE_GENERATION, runtimeGeneration)
                ruleUpdate?.let {
                    putExtra(RRVpnService.EXTRA_RULE_UPDATE_CANDIDATE_GENERATION, it.generation)
                    putExtra(RRVpnService.EXTRA_RULE_UPDATE_BASE_GENERATION, it.baseGeneration)
                    putExtra(RRVpnService.EXTRA_RULE_UPDATE_OPERATION, it.operation)
                }
            }
        )
    }

    private fun sendStopVpn() {
        routingRestartJob?.cancel()
        com.rr.client.vpn.VpnConnectionIntentStore.setDesiredRunning(this, false)
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
        if (clearingApplicationData) return
        clearingApplicationData = true
        routingRestartJob?.cancel()
        clearPendingVpn()
        lifecycleScope.launch {
            var teardownComplete = false
            try {
                check(RRVpnService.awaitStopForReset(this@MainActivity)) {
                    "引擎清理尚未确认，已暂停清除数据，请重试"
                }
                teardownComplete = true
                val manager = getSystemService(ActivityManager::class.java) ?: error("ActivityManager unavailable")
                check(manager.clearApplicationUserData()) { "Android 拒绝清除应用数据请求" }
            } catch (error: Exception) {
                if (teardownComplete) RRVpnService.cancelPendingReset()
                if (error is CancellationException) throw error
                Toast.makeText(
                    this@MainActivity,
                    "无法自动清除 RRBOX 数据：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                clearingApplicationData = false
            }
        }
    }
}

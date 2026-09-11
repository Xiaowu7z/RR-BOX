package com.rr.client.vpn

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rr.client.RRApplication
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.AppNodeRuntimeValidation
import com.rr.client.lab.RRLogStore
import com.rr.client.routing.AppNodeRouting
import com.rr.client.routing.AppNodeBindingRevision
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.subscription.TrafficInfoNode
import com.rr.client.subscription.model.SubProfile
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

object RRQuickTileController {
    data class QuickConnectResult(
        val nodeTag: String,
        val usedCachedRuntime: Boolean,
        val prepareMillis: Long
    )

    suspend fun connect(context: Context): Result<QuickConnectResult> = prepareAndStart(context, recovery = false)

    internal data class PreparedRuntime(val state: VpnRuntimeState, val cached: Boolean, val bindingRevision: Long)

    private suspend fun prepareAndStart(context: Context, recovery: Boolean): Result<QuickConnectResult> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            if (recovery) check(VpnConnectionIntentStore.isDesiredRunning(context)) { "用户已断开，跳过恢复" }
            val forcedMainNodeId = if (recovery) {
                RRVpnService.activeRuntimeNodeId.value?.takeIf(String::isNotBlank)
                    ?: VpnRuntimeStateStore(context).load()?.nodeId
                    ?: error("没有可恢复的最近运行主节点")
            } else null
            val prepared = prepareRuntime(context, forcedMainNodeId)
            if (!prepared.cached) VpnRuntimeStateStore(context).save(prepared.state)
            currentCoroutineContext().ensureActive()
            startRuntime(context, prepared, recovery)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (prepared.cached) {
                Log.i(TAG, "Quick tile fast path: cached runtime · node=${prepared.state.nodeTag} · prepare=${elapsed}ms")
            } else {
                Log.i(TAG, "Quick tile rebuilt runtime · node=${prepared.state.nodeTag} · prepare=${elapsed}ms")
            }
            QuickConnectResult(prepared.state.nodeTag, prepared.cached, elapsed)
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Internal restarts keep the live main exit, independently of any UI selection. */
    internal suspend fun rebuildRuntime(context: Context, mainNodeId: String): PreparedRuntime = withContext(Dispatchers.IO) {
        require(mainNodeId.isNotBlank()) { "没有可恢复的当前主节点" }
        prepareRuntime(context, mainNodeId)
    }

    private suspend fun prepareRuntime(context: Context, forcedMainNodeId: String?): PreparedRuntime {
        val bindingRevision = AppNodeBindingRevision.current()
        val app = RRApplication.instance
        val prefs = app.preferencesManager
        val storedId = prefs.selectedNodeId.first()
        val smartRouting = prefs.smartRouting.first()
        val fastForwarding = prefs.fastForwarding.first()
        val perAppMode = prefs.perAppMode.first()
        val selectedPackages = selectedPackagesForMode(prefs, perAppMode)
        val appNodeBindings = prefs.appNodeBindings.first()
        val cached = VpnRuntimeStateStore(context).load()
        val profiles = app.database.profileDao().getAllProfiles().map { SubProfile.fromEntity(it) }
        val overrides = prefs.nodeOverrides.first()
        val allNodes = profiles.flatMap { it.nodes }.map { node ->
            com.rr.client.core.NodeOverridePatcher.resolve(node, overrides[node.id])
        }
        val selectableNodes = allNodes.filterNot(TrafficInfoNode::isInfoNode)
        val targetId = QuickTileRuntimePolicy.resolveMainNodeId(
            selectableNodes.map { it.id }, storedId, forcedMainNodeId
        )
        val targetNode = selectableNodes.first { it.id == targetId }
        val usableNodes = AppNodeRuntimeValidation.filterUsableNodes(
            context, targetNode, allNodes, appNodeBindings, perAppMode, selectedPackages
        )
        val ruleSets = if (smartRouting) ChinaRuleSetManager.ensureBundled(context).getOrThrow() else null
        val configJson = ConfigBuilder.buildSingBoxConfig(
            selectedNode = targetNode,
            allNodes = usableNodes,
            appRoutes = emptyList(),
            smartRouting = smartRouting,
            perAppMode = perAppMode,
            selectedPackages = selectedPackages,
            fastForwarding = fastForwarding,
            ruleSets = ruleSets,
            routingPolicy = ruleSets?.policy ?: com.rr.client.routing.RoutingPolicySnapshot.bundled(),
            appNodeBindings = appNodeBindings
        )
        RRLogStore.record("APP", "连接配置准备；智能分流=$smartRouting；" +
            "策略版本=${ruleSets?.policy?.ruleVersion ?: 0L}；规则包=${ruleSets?.bundleVersion ?: 0L}")
        check(bindingRevision == AppNodeBindingRevision.current()) { "应用指定节点已更新，请重新连接" }
        // Fresh canonical generation detects changed credentials, subscriptions and bindings.
        if (cached != null && QuickTileRuntimePolicy.matches(
                state = cached,
                selectedNodeId = targetNode.id,
                smartRouting = smartRouting,
                fastForwarding = fastForwarding,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                expectedConfigJson = configJson,
                appNodeBindings = appNodeBindings
            )
        ) return PreparedRuntime(cached.copy(nodeTag = targetNode.tag), cached = true, bindingRevision = bindingRevision)

        Libbox.checkConfig(configJson)
        if (forcedMainNodeId == null) prefs.setSelectedNodeId(targetNode.id)
        check(bindingRevision == AppNodeBindingRevision.current()) { "应用指定节点已更新，请重新连接" }
        return PreparedRuntime(VpnRuntimeState(
            configJson = configJson,
            nodeTag = targetNode.tag,
            nodeId = targetNode.id,
            perAppMode = perAppMode,
            selectedPackages = selectedPackages,
            smartRouting = smartRouting,
            fastForwarding = fastForwarding,
            appNodeBindings = appNodeBindings
        ), cached = false, bindingRevision = bindingRevision)
    }

    /**
     * Rebuild from current persisted bindings/nodes before recovering. A removed or changed
     * auxiliary node must not be revived from a formerly valid runtime cache.
     */
    suspend fun recoverLastRuntime(context: Context): Result<String> =
        prepareAndStart(context, recovery = true).map { it.nodeTag }

    fun stop(context: Context) {
        RRLogStore.record("APP", "快捷按钮请求断开；当前引擎=${RRVpnService.activeRuntimeEngine.value.orEmpty()}")
        VpnConnectionIntentStore.setDesiredRunning(context, false)
        context.startService(
            Intent(context, RRVpnService::class.java).apply {
                action = RRNotificationManager.ACTION_STOP_VPN
            }
        )
    }

    private suspend fun startRuntime(context: Context, prepared: PreparedRuntime, recovery: Boolean) {
        val runtime = prepared.state
        check(com.rr.client.storage.ProfileNodeStore.containsConnectableNodes(
            RRApplication.instance.database, AppNodeRouting.requiredNodeIds(runtime.configJson, runtime.nodeId)
        )) { "节点已删除或不可用，请在 RRBOX 中重新选择节点" }
        if (recovery) check(VpnConnectionIntentStore.isDesiredRunning(context)) { "用户已断开，跳过恢复" }
        check(prepared.bindingRevision == AppNodeBindingRevision.current()) { "应用指定节点已更新，请重新连接" }
        RRLogStore.record("APP", "快捷按钮请求连接；配置=${if (prepared.cached) "已核对当前规则的缓存" else "重新生成"}")
        ContextCompat.startForegroundService(
            context,
            Intent(context, RRVpnService::class.java).apply {
                if (recovery) {
                    action = RRNotificationManager.ACTION_RESTART_VPN
                    putExtra(RRVpnService.EXTRA_RECOVERY_REQUEST, true)
                }
                putExtra(RRVpnService.EXTRA_CONFIG_JSON, runtime.configJson)
                putExtra(RRVpnService.EXTRA_NODE_TAG, runtime.nodeTag)
                putExtra(RRVpnService.EXTRA_NODE_ID, runtime.nodeId)
                putExtra(RRVpnService.EXTRA_APP_BINDING_REVISION, prepared.bindingRevision)
            }
        )
    }

    private suspend fun selectedPackagesForMode(
        prefs: com.rr.client.storage.PreferencesManager,
        perAppMode: String
    ): Set<String> = when (perAppMode) {
        PerAppPolicyResolver.MODE_ALLOW_LIST -> prefs.proxySelectedAppPackages.first()
        PerAppPolicyResolver.MODE_DISALLOW_LIST -> prefs.bypassSelectedAppPackages.first()
        else -> emptySet()
    }

    private const val TAG = "RRQuickTile"
}

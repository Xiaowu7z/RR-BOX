package com.rr.client.vpn

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rr.client.RRApplication
import com.rr.client.core.ConfigBuilder
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

    suspend fun connect(context: Context): Result<QuickConnectResult> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching {
            val app = RRApplication.instance
            val prefs = app.preferencesManager

            val storedId = prefs.selectedNodeId.first()
            val smartRouting = prefs.smartRouting.first()
            val fastForwarding = prefs.fastForwarding.first()
            val perAppMode = prefs.perAppMode.first()
            val selectedPackages = selectedPackagesForMode(prefs, perAppMode)

            val store = VpnRuntimeStateStore(context)
            val cached = store.load()
            val profiles = app.database.profileDao()
                .getAllProfiles()
                .map { entity -> SubProfile.fromEntity(entity) }

            val overrides = prefs.nodeOverrides.first()
            val allNodes = profiles
                .flatMap { it.nodes }
                .map { node -> com.rr.client.core.NodeOverridePatcher.resolve(node, overrides[node.id]) }
            val selectableNodes = allNodes.filterNot(TrafficInfoNode::isInfoNode)

            require(selectableNodes.isNotEmpty()) {
                "还没有可用节点，请先在 RRBOX 中添加节点或订阅"
            }

            val targetNode = selectableNodes.firstOrNull { it.id == storedId } ?: selectableNodes.first()
            val ruleSets = if (smartRouting) {
                ChinaRuleSetManager.ensureBundled(context).getOrThrow()
            } else {
                null
            }

            val configJson = ConfigBuilder.buildSingBoxConfig(
                selectedNode = targetNode,
                allNodes = allNodes,
                appRoutes = emptyList(),
                smartRouting = smartRouting,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                fastForwarding = fastForwarding,
                ruleSets = ruleSets,
                routingPolicy = ruleSets?.policy ?: com.rr.client.routing.RoutingPolicySnapshot.bundled()
            )
            // Validate against CURRENT persisted nodes/settings before using a cached runtime.
            // ConfigBuilder does not need installed-app enumeration for per-app include/exclude.
            if (cached != null && QuickTileRuntimePolicy.matches(
                    state = cached,
                    selectedNodeId = storedId,
                    smartRouting = smartRouting,
                    fastForwarding = fastForwarding,
                    perAppMode = perAppMode,
                    selectedPackages = selectedPackages,
                    expectedConfigJson = configJson
                )
            ) {
                currentCoroutineContext().ensureActive()
                val current = cached.copy(nodeTag = targetNode.tag)
                startRuntime(context, current)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "Quick tile fast path: cached runtime · node=${current.nodeTag} · prepare=${elapsed}ms")
                return@runCatching QuickConnectResult(
                    nodeTag = current.nodeTag,
                    usedCachedRuntime = true,
                    prepareMillis = elapsed
                )
            }

            Libbox.checkConfig(configJson)

            prefs.setSelectedNodeId(targetNode.id)
            val runtime = VpnRuntimeState(
                configJson = configJson,
                nodeTag = targetNode.tag,
                nodeId = targetNode.id,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                smartRouting = smartRouting,
                fastForwarding = fastForwarding
            )
            store.save(runtime)
            currentCoroutineContext().ensureActive()
            startRuntime(context, runtime)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "Quick tile rebuilt runtime · node=${targetNode.tag} · prepare=${elapsed}ms")
            QuickConnectResult(
                nodeTag = targetNode.tag,
                usedCachedRuntime = false,
                prepareMillis = elapsed
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Used by the continuity guard after an unexpected data-plane stop.
     * It deliberately reuses only the last already-validated runtime config and never rebuilds rules/apps.
     */
    suspend fun recoverLastRuntime(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(VpnConnectionIntentStore.isDesiredRunning(context)) { "用户已断开，跳过恢复" }
            val cached = VpnRuntimeStateStore(context).load()
                ?: error("没有可恢复的最近运行配置")
            ContextCompat.startForegroundService(
                context,
                Intent(context, RRVpnService::class.java).apply {
                    action = RRNotificationManager.ACTION_RESTART_VPN
                    putExtra(RRVpnService.EXTRA_RECOVERY_REQUEST, true)
                    putExtra(RRVpnService.EXTRA_CONFIG_JSON, cached.configJson)
                    putExtra(RRVpnService.EXTRA_NODE_TAG, cached.nodeTag)
                    putExtra(RRVpnService.EXTRA_NODE_ID, cached.nodeId)
                }
            )
            cached.nodeTag
        }
    }

    fun stop(context: Context) {
        VpnConnectionIntentStore.setDesiredRunning(context, false)
        context.startService(
            Intent(context, RRVpnService::class.java).apply {
                action = RRNotificationManager.ACTION_STOP_VPN
            }
        )
    }

    private fun startRuntime(context: Context, runtime: VpnRuntimeState) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RRVpnService::class.java).apply {
                putExtra(RRVpnService.EXTRA_CONFIG_JSON, runtime.configJson)
                putExtra(RRVpnService.EXTRA_NODE_TAG, runtime.nodeTag)
                putExtra(RRVpnService.EXTRA_NODE_ID, runtime.nodeId)
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

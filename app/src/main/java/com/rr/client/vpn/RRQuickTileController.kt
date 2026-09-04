package com.rr.client.vpn

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rr.client.RRApplication
import com.rr.client.core.ConfigBuilder
import com.rr.client.routing.AppManager
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.subscription.model.SubProfile
import io.nekohasekai.libbox.Libbox
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
            if (cached != null && QuickTileRuntimePolicy.matches(
                    state = cached,
                    selectedNodeId = storedId,
                    smartRouting = smartRouting,
                    fastForwarding = fastForwarding,
                    perAppMode = perAppMode,
                    selectedPackages = selectedPackages
                )
            ) {
                startRuntime(context, cached)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "Quick tile fast path: cached runtime · node=${cached.nodeTag} · prepare=${elapsed}ms")
                return@runCatching QuickConnectResult(
                    nodeTag = cached.nodeTag,
                    usedCachedRuntime = true,
                    prepareMillis = elapsed
                )
            }

            val profiles = app.database.profileDao()
                .getAllProfiles()
                .map { entity -> SubProfile.fromEntity(entity) }

            val overrides = prefs.nodeOverrides.first()
            val allNodes = profiles
                .flatMap { it.nodes }
                .map { node -> overrides[node.id] ?: node }

            require(allNodes.isNotEmpty()) {
                "还没有可用节点，请先在 RRBOX 中添加节点或订阅"
            }

            val targetNode = allNodes.firstOrNull { it.id == storedId } ?: allNodes.first()
            val apps = AppManager(context).getInstalledApps(includeSystem = false)
            val ruleSets = if (smartRouting) {
                ChinaRuleSetManager.ensureBundled(context).getOrNull()
            } else {
                null
            }

            val configJson = ConfigBuilder.buildSingBoxConfig(
                selectedNode = targetNode,
                allNodes = allNodes,
                appRoutes = apps,
                smartRouting = smartRouting,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                fastForwarding = fastForwarding,
                ruleSets = ruleSets
            )
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
            startRuntime(context, runtime)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(TAG, "Quick tile rebuilt runtime · node=${targetNode.tag} · prepare=${elapsed}ms")
            QuickConnectResult(
                nodeTag = targetNode.tag,
                usedCachedRuntime = false,
                prepareMillis = elapsed
            )
        }
    }

    /**
     * Used by the continuity guard after an unexpected data-plane stop.
     * It deliberately reuses only the last already-validated runtime config and never rebuilds rules/apps.
     */
    suspend fun recoverLastRuntime(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cached = VpnRuntimeStateStore(context).load()
                ?: error("没有可恢复的最近运行配置")
            ContextCompat.startForegroundService(
                context,
                Intent(context, RRVpnService::class.java).apply {
                    action = RRNotificationManager.ACTION_RESTART_VPN
                    putExtra(RRVpnService.EXTRA_CONFIG_JSON, cached.configJson)
                    putExtra(RRVpnService.EXTRA_NODE_TAG, cached.nodeTag)
                    putExtra(RRVpnService.EXTRA_NODE_ID, cached.nodeId)
                }
            )
            cached.nodeTag
        }
    }

    fun stop(context: Context) {
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

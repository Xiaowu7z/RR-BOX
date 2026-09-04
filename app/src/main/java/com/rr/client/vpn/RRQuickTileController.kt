package com.rr.client.vpn

import android.content.Context
import android.content.Intent
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
    suspend fun connect(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val app = RRApplication.instance
            val prefs = app.preferencesManager

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

            val storedId = prefs.selectedNodeId.first()
            val targetNode = allNodes.firstOrNull { it.id == storedId } ?: allNodes.first()

            val smartRouting = prefs.smartRouting.first()
            val fastForwarding = prefs.fastForwarding.first()
            val perAppMode = prefs.perAppMode.first()
            val selectedPackages = when (perAppMode) {
                PerAppPolicyResolver.MODE_ALLOW_LIST -> prefs.proxySelectedAppPackages.first()
                PerAppPolicyResolver.MODE_DISALLOW_LIST -> prefs.bypassSelectedAppPackages.first()
                else -> emptySet()
            }

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
            VpnRuntimeStateStore(context).save(
                VpnRuntimeState(
                    configJson = configJson,
                    nodeTag = targetNode.tag,
                    nodeId = targetNode.id,
                    perAppMode = perAppMode,
                    selectedPackages = selectedPackages
                )
            )

            ContextCompat.startForegroundService(
                context,
                Intent(context, RRVpnService::class.java).apply {
                    putExtra(RRVpnService.EXTRA_CONFIG_JSON, configJson)
                    putExtra(RRVpnService.EXTRA_NODE_TAG, targetNode.tag)
                    putExtra(RRVpnService.EXTRA_NODE_ID, targetNode.id)
                }
            )
            targetNode.tag
        }
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RRVpnService::class.java).apply {
                action = RRNotificationManager.ACTION_STOP_VPN
            }
        )
    }
}

package com.rr.client.core

import android.content.Context
import com.rr.client.core.model.ProxyNode
import com.rr.client.lab.RRLogStore
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.AppNodeRouting
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException

/** Called on an IO dispatcher before either UI or Quick Settings builds the runtime. */
object AppNodeRuntimeValidation {
    fun filterUsableNodes(
        context: Context,
        selectedNode: ProxyNode,
        allNodes: List<ProxyNode>,
        bindings: List<AppNodeBinding>,
        perAppMode: String,
        selectedPackages: Set<String>
    ): List<ProxyNode> {
        val active = AppNodeRouting.activeBindings(bindings, perAppMode, selectedPackages)
        if (active.isEmpty()) return allNodes

        validateSharedUidTargets(context, bindings, perAppMode, selectedPackages, selectedNode.id)

        val auxiliaryIds = active.mapTo(hashSetOf()) { it.nodeId } - selectedNode.id
        val invalidIds = hashSetOf<String>()
        allNodes.filter { it.id in auxiliaryIds }.distinctBy { it.id }.forEach { node ->
            try {
                // Validate each auxiliary node independently. Keep its binding if it is
                // invalid: ConfigBuilder then rejects only that application's traffic.
                Libbox.checkConfig(ConfigBuilder.buildSingBoxConfig(
                    selectedNode = node,
                    allNodes = listOf(node),
                    appRoutes = emptyList(),
                    smartRouting = false
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                invalidIds += node.id
                RRLogStore.record("APP", "应用指定节点配置无效，已暂停对应应用连接：${node.tag}")
            }
        }
        return if (invalidIds.isEmpty()) allNodes else allNodes.filterNot { it.id in invalidIds }
    }

    fun validateSharedUidTargets(
        context: Context,
        bindings: List<AppNodeBinding>,
        perAppMode: String,
        selectedPackages: Set<String>,
        mainNodeId: String? = null
    ) {
        val active = AppNodeRouting.activeBindings(bindings, perAppMode, selectedPackages)

        // Android attributes sockets to UIDs. Two packages sharing a UID cannot select
        // different outlets, regardless of the TUN engine used for capture.
        val packagesByUid = mutableMapOf<Int, Set<String>>()
        active.forEach { binding ->
            val uid = try {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(binding.packageName, 0).uid
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                return@forEach
            }
            packagesByUid.getOrPut(uid) {
                context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty() + binding.packageName
            }
        }
        AppNodeRouting.validateSharedUidTargets(active, packagesByUid, mainNodeId)
    }
}

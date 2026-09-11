package com.rr.client.core

import android.content.Context
import android.content.pm.PackageManager
import com.rr.client.core.model.ProxyNode
import com.rr.client.lab.AppRoutingDiagnostics
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.AppNodeRouting
import com.rr.client.routing.AppNodeUidGroup
import com.rr.client.routing.AppNodeUidGroupPolicy
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException

/** Called on an IO dispatcher before either UI or Quick Settings builds the runtime. */
object AppNodeRuntimeValidation {
    /** Resolve every installed sibling, including apps hidden from the launcher/list. */
    fun resolveEditingGroup(context: Context, packageName: String): AppNodeUidGroup {
        val pm = context.packageManager
        val appInfo = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return AppNodeUidGroup(null, setOf(packageName))
        }
        val packages = pm.getPackagesForUid(appInfo.uid)?.toSet().orEmpty()
        require(packageName in packages) { "无法完整读取应用共享身份，请重试" }
        val members = packages.associateWith { member ->
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(member, 0).also {
                require(it.uid == appInfo.uid) { "应用身份正在变化，请稍后重试" }
            }
        }
        val blockedReason = AppNodeUidGroupPolicy.blockedReason(
            appInfo.uid, packages, android.os.Process.myUid(), context.packageName
        )
        return AppNodeUidGroup(appInfo.uid, packages,
            members.mapValues { (_, info) -> pm.getApplicationLabel(info).toString() }, blockedReason)
    }

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
            } catch (error: Exception) {
                invalidIds += node.id
                AppRoutingDiagnostics.record("辅助节点配置检查失败；节点=${node.tag.filterNot(Char::isISOControl).take(120)}；" +
                    "标识=${AppRoutingDiagnostics.nodeKey(node.id)}；异常=${error.javaClass.simpleName}；" +
                    "原因=${error.message.orEmpty().take(1000)}；对应应用将阻断，其他出口继续工作")
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

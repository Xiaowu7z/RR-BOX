package com.rr.client.lab

import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.AppNodeBindingEditPlan
import com.rr.client.routing.AppNodeBindingEdit
import java.util.UUID

/** Correlates a UI edit with its durable commit and service dispatch, including failures. */
object AppNodeEditLog {
    fun begin(operation: String, packageName: String, mode: String, engine: String): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        event(id, "尝试", "操作=${line(operation)}；应用=${line(packageName)}；接管模式=$mode；当前运行引擎=$engine")
        return id
    }

    fun event(id: String, stage: String, detail: String) {
        AppRoutingDiagnostics.record("应用指定节点；操作ID=$id；阶段=$stage；$detail")
    }

    fun failure(id: String, stage: String, error: Exception) {
        event(id, stage, "异常=${error.javaClass.simpleName}；原因=${line(error.message ?: "未提供", 1200)}")
    }

    fun requested(id: String, edit: AppNodeBindingEdit, nodes: List<ProxyNode>) {
        selectedTarget(id, edit.nodeId, edit.enabled, nodes)
        event(id, "已确认共享身份", "应用=${line(edit.packageName)}；UID=${edit.confirmedGroup.uid ?: "未安装"}")
        edit.confirmedGroup.packages.sorted().forEach { name ->
            event(id, "已确认共享成员", "应用=${line(name)}；名称=${line(edit.confirmedGroup.labels[name].orEmpty())}")
        }
    }

    fun selectedTarget(id: String, nodeId: String?, enabled: Boolean?, nodes: List<ProxyNode>) {
        val target = nodeId?.let {
            val name = nodes.firstOrNull { it.id == nodeId }?.tag?.let { line(it) } ?: "节点未加载"
            "$name (${AppRoutingDiagnostics.nodeKey(nodeId)})"
        } ?: "沿用当前规则"
        event(id, "用户选择", "节点=$target；启用=${enabled ?: "保持"}")
    }

    fun planned(id: String, plan: AppNodeBindingEditPlan, nodes: List<ProxyNode>) {
        val previous = plan.expectedBindings.associateBy { it.packageName }
        val updated = plan.bindings.associateBy { it.packageName }
        val knownNodes = nodes.associateBy { it.id }
        event(id, "校验通过", "共享UID=${plan.group.uid ?: "未安装"}；成员数=${plan.group.packages.size}；" +
            "接管模式=${plan.expectedMode}；代理名单变化=${plan.proxyPackages != plan.expectedProxyPackages}；" +
            "绕过名单变化=${plan.bypassPackages != plan.expectedBypassPackages}")
        (previous.keys + updated.keys).sorted().forEach { name ->
            val before = previous[name]
            val after = updated[name]
            if (before != after) {
                val destination = after?.let { binding ->
                    val label = knownNodes[binding.nodeId]?.tag?.let { line(it) } ?: "节点未加载"
                    "$label (${AppRoutingDiagnostics.nodeKey(binding.nodeId)})；启用=${binding.enabled}"
                } ?: "移除规则"
                event(id, "计划变更", "应用=${line(name)}；目标=$destination；尚待保存")
            }
        }
    }

    private fun line(value: String, limit: Int = 160): String =
        value.filterNot(Char::isISOControl).take(limit)
}

package com.rr.client.routing

/** The PackageManager identity shown to the user and checked again before saving. */
data class AppNodeUidGroup(
    val uid: Int?,
    val packages: Set<String>,
    val labels: Map<String, String> = emptyMap(),
    val blockedReason: String? = null
) {
    fun sameIdentity(other: AppNodeUidGroup): Boolean =
        uid == other.uid && packages == other.packages && blockedReason == other.blockedReason
}

object AppNodeUidGroupPolicy {
    fun blockedReason(uid: Int, packages: Set<String>, ownUid: Int, ownPackage: String): String? = when {
        ownPackage in packages || uid == ownUid -> "不能为 RRBOX 自身或共享其身份的应用指定节点"
        uid % 100_000 !in 10_000..19_999 -> "系统核心或隔离身份不能单独指定节点"
        else -> null
    }
}

enum class AppNodeEditOperation { ASSIGN, SET_ENABLED, DELETE }

data class AppNodeBindingEdit(
    val packageName: String,
    val operation: AppNodeEditOperation,
    val nodeId: String? = null,
    val enabled: Boolean? = null,
    val confirmedGroup: AppNodeUidGroup
)

data class AppNodeBindingEditPlan(
    val expectedBindings: List<AppNodeBinding>,
    val expectedMode: String,
    val expectedProxyPackages: Set<String>,
    val expectedBypassPackages: Set<String>,
    val bindings: List<AppNodeBinding>,
    val proxyPackages: Set<String>,
    val bypassPackages: Set<String>,
    val proxyAutoInclusions: Set<String>,
    val group: AppNodeUidGroup,
    val operation: AppNodeEditOperation
)

/** One consented UID is edited atomically. Runtime conflict validation remains separate. */
object AppNodeBindingEditPlanner {
    fun plan(
        edit: AppNodeBindingEdit,
        currentGroup: AppNodeUidGroup,
        bindings: List<AppNodeBinding>,
        mode: String,
        proxyPackages: Set<String>,
        bypassPackages: Set<String>
    ): AppNodeBindingEditPlan {
        require(edit.confirmedGroup.sameIdentity(currentGroup)) {
            "应用的共享身份已变化，请重新选择并确认"
        }
        val group = currentGroup.packages
        require(edit.packageName in group && group.isNotEmpty()) { "无法确认应用身份，请重新选择" }
        require(mode in setOf("ALL", "ALLOW_LIST", "DISALLOW_LIST")) { "未知分应用模式" }
        val existing = bindings.firstOrNull { it.packageName == edit.packageName }
        val replacement = when (edit.operation) {
            AppNodeEditOperation.DELETE -> null
            AppNodeEditOperation.ASSIGN -> {
                require(!edit.nodeId.isNullOrBlank()) { "请选择有效节点" }
                AppNodeBinding(edit.packageName, edit.nodeId, existing?.enabled ?: true)
            }
            AppNodeEditOperation.SET_ENABLED -> {
                require(existing != null && edit.enabled != null) { "应用规则已变化，请重新打开" }
                existing.copy(enabled = edit.enabled)
            }
        }
        // Disabling/removing a stale or protected identity must always remain possible.
        if (replacement != null && (edit.operation == AppNodeEditOperation.ASSIGN || replacement.enabled)) {
            require(currentGroup.uid != null) { "应用未安装，无法设置指定节点" }
            require(currentGroup.blockedReason == null) { currentGroup.blockedReason.orEmpty() }
        }
        val updated = when {
            replacement == null -> bindings.filterNot { it.packageName in group }
            edit.operation == AppNodeEditOperation.SET_ENABLED && !replacement.enabled -> {
                // Cleanup never creates entries for system/self siblings (some cannot
                // be represented as ordinary app bindings in the persistent codec).
                bindings.map { if (it.packageName in group) it.copy(enabled = false) else it }
            }
            else -> bindings.filterNot { it.packageName in group } +
                group.map { replacement.copy(packageName = it) }
        }.sortedBy(AppNodeBinding::packageName)
        // Only normalize this mode's consented group. The other saved capture list is
        // independent; a later conflicting mode switch remains subject to validation.
        // In DISALLOW_LIST a partially bypassed UID is actually bypassed by Android:
        // the confirmation explicitly explains removal of all group bypass entries.
        // Delete/disable retain capture scope and therefore resume default routing.
        val requestedInScope = when (mode) {
            "ALLOW_LIST" -> group.any { it in proxyPackages }
            "DISALLOW_LIST" -> group.any { it !in bypassPackages }
            else -> true
        }
        val captureGroup = replacement?.enabled == true && requestedInScope
        return AppNodeBindingEditPlan(
            bindings, mode, proxyPackages, bypassPackages, updated,
            if (captureGroup && mode == "ALLOW_LIST") proxyPackages + group else proxyPackages,
            if (captureGroup && mode == "DISALLOW_LIST") bypassPackages - group else bypassPackages,
            if (captureGroup && mode == "ALLOW_LIST") group else emptySet(),
            currentGroup, edit.operation
        )
    }
}

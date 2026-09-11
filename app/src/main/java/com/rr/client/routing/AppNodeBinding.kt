package com.rr.client.routing

import com.google.gson.JsonParser
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Base64

/** A stable node identity, independent of a subscription's editable display name. */
data class AppNodeBinding(
    val packageName: String,
    val nodeId: String,
    val enabled: Boolean = true
)

object AppNodeRouting {
    private const val SELF_PACKAGE = "com.rr.client"
    private const val NODE_TAG_PREFIX = "rr-app-node-"
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    /** Only captured applications can acquire an extra outlet. This never widens capture. */
    fun activeBindings(
        bindings: List<AppNodeBinding>,
        perAppMode: String,
        selectedPackages: Set<String>
    ): List<AppNodeBinding> {
        require(perAppMode in setOf(
            PerAppPolicyResolver.MODE_ALL,
            PerAppPolicyResolver.MODE_ALLOW_LIST,
            PerAppPolicyResolver.MODE_DISALLOW_LIST
        )) { "未知分应用模式：$perAppMode" }
        val selected = selectedPackages.map(String::trim).toSet()
        val active = bindings.asSequence()
            .filter { it.enabled }
            .map { it.copy(packageName = it.packageName.trim()) }
            .filter { it.packageName != SELF_PACKAGE && packagePattern.matches(it.packageName) }
            .filter { binding ->
                when (perAppMode) {
                    PerAppPolicyResolver.MODE_ALLOW_LIST -> binding.packageName in selected
                    PerAppPolicyResolver.MODE_DISALLOW_LIST -> binding.packageName !in selected
                    else -> true
                }
            }
            .toList()
        return active.groupBy { it.packageName }.toSortedMap().map { (packageName, entries) ->
            require(entries.map { it.nodeId }.distinct().size == 1) {
                "应用 $packageName 绑定了多个不同节点，请保留一个"
            }
            // Preserve missing/blank node identities: the builder must reject this app's
            // traffic instead of silently returning it to the main outlet.
            entries.first()
        }
    }

    fun nodeTag(nodeId: String): String {
        require(nodeId.isNotBlank()) { "节点 ID 不能为空" }
        return NODE_TAG_PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(nodeId.toByteArray(Charsets.UTF_8))
    }

    fun nodeIdFromTag(tag: String): String? {
        if (!tag.startsWith(NODE_TAG_PREFIX)) return null
        val encoded = tag.removePrefix(NODE_TAG_PREFIX)
        if (!encoded.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        // Reject malformed UTF-8, padding and noncanonical aliases of a node identity.
        return decoded.takeIf { nodeTag(it) == tag }
    }

    /** HEV performs this check before SOCKS conversion and removes only this exact rule. */
    fun unknownOwnerGuard(): JsonObject = JsonObject().apply {
        addProperty("type", "logical")
        addProperty("mode", "and")
        add("rules", JsonArray().apply {
            add(JsonObject().apply {
                add("network", JsonArray().apply { add("tcp"); add("udp") })
            })
            add(JsonObject().apply {
                add("package_name_regex", JsonArray().apply { add(".+") })
                addProperty("invert", true)
            })
        })
        addProperty("action", "reject")
    }

    fun isUnknownOwnerGuard(rule: JsonObject): Boolean = rule == unknownOwnerGuard()

    /**
     * Android captures and identifies sockets by UID, including unselected sibling
     * packages. A sibling without an active binding is expected to use the main node.
     */
    fun validateSharedUidTargets(
        activeBindings: List<AppNodeBinding>,
        packagesByUid: Map<Int, Set<String>>,
        mainNodeId: String?
    ) {
        val targets = activeBindings.associate { it.packageName to it.nodeId }
        packagesByUid.values.forEach { packages ->
            if (packages.none(targets::containsKey)) return@forEach
            val outlets = packages.map { name -> targets[name] ?: mainNodeId }.toSet()
            require(outlets.size <= 1) {
                "共享系统身份的应用必须使用同一个节点，请统一这些应用的设置：${packages.sorted().joinToString("、")}"
            }
        }
    }

    /** Actual live outbounds, excluding missing bindings which became reject rules. */
    fun requiredNodeIds(configJson: String, mainNodeId: String): Set<String> {
        val root = JsonParser.parseString(configJson).asJsonObject
        val outbounds = requireNotNull(root.getAsJsonArray("outbounds")) { "配置缺少节点出口" }
        return buildSet {
            if (mainNodeId.isNotBlank()) add(mainNodeId)
            outbounds.forEach { element ->
                val tag = element.asJsonObject.get("tag")
                if (tag != null && tag.isJsonPrimitive) nodeIdFromTag(tag.asString)?.let(::add)
            }
        }
    }
}

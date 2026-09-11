package com.rr.client.lab

import com.rr.client.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.routing.AppNodeRouting
import com.rr.client.security.SecretRedactor
import java.security.MessageDigest

/** Diagnostics hold only immutable, credential-free projections of the configuration being used. */
object AppRoutingDiagnostics {
    const val CHANNEL = "POLICY"
    private val internalNodeTag = Regex("rr-app-node-[A-Za-z0-9_-]+")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    fun nodeKey(nodeId: String): String = runCatching {
        if (nodeId.isBlank()) "节点未知" else "节点#${digest(nodeId)}"
    }.getOrDefault("节点摘要不可用")

    fun safeText(message: String): String = runCatching {
        SecretRedactor.redact(internalNodeTag.replace(message) {
            AppNodeRouting.nodeIdFromTag(it.value)?.let(::nodeKey) ?: "节点标签#${digest(it.value)}"
        })
    }.getOrDefault("诊断内容无法安全显示")

    /** Never let diagnostics failure alter activation, cleanup, or native owner resolution. */
    fun record(message: String) { runCatching { RRLogStore.record(CHANNEL, "RRBOX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})；" + safeText(message)) } }

    internal fun outletKey(tag: String): String = when (tag) {
        "proxy", "direct", "block", "reject", "dns-out" -> tag
        "" -> "未知"
        else -> AppNodeRouting.nodeIdFromTag(tag)?.let(::nodeKey) ?: "出口#${digest(tag)}"
    }

    data class RuntimeSnapshot(
        val engine: String,
        val generation: Long,
        val mainNode: String,
        val bindingVersion: String,
        val scope: String,
        val bindings: Map<String, String>,
        val loadedOutlets: Set<String>,
        val detailEnabled: Boolean,
        val unknownOwnerBlocked: Boolean,
        val entrancePackages: Map<String, List<String>> = emptyMap()
    ) {
        val context: String get() = "引擎=$engine；运行代次=$generation；实际绑定配置摘要=$bindingVersion；主节点=$mainNode"

        fun event(stage: String, reason: String = ""): String = buildString {
            append(stage).append("；").append(context)
            if (reason.isNotBlank()) append("；原因=").append(safeText(reason).take(1600))
        }

        fun loadedMessages(): List<String> = buildList {
            add(event("运行配置已加载") + "；应用接管=$scope；绑定应用=${bindings.size}；" +
                "逐连接采集=${if (detailEnabled) "开启" else "关闭"}；" +
                "身份不明连接=${if (unknownOwnerBlocked) "阻断（共享 DNS 另行处理）" else "由原有规则处理"}；" +
                "仅确认规则加载，业务是否成功需结合连接结果")
            bindings.entries.groupBy({ it.value }, { it.key }).forEach { (outlet, packages) ->
                packages.sorted().chunked(12).forEach { names ->
                    val result = when {
                        outlet == "reject" -> "阻断（目标节点缺失或不可用）"
                        outlet in loadedOutlets -> "已加载"
                        else -> "未确认出口加载"
                    }
                    add("应用出口映射；$context；应用=${names.joinToString(",")}；出口=$outlet；结果=$result")
                }
            }
            entrancePackages.forEach { (entrance, packages) ->
                packages.chunked(12).forEach { names ->
                    add("HEV 入口映射；$context；入口=$entrance；绑定应用候选=${names.joinToString(",")}；" +
                        "连接中未携带原始 UID 时，不据此确认实际进程")
                }
            }
        }
    }

    /** canonicalConfig is the exact source used to produce effectiveConfig, never a later preference. */
    fun snapshot(
        engine: String,
        generation: Long,
        mainNodeId: String,
        canonicalConfig: String,
        effectiveConfig: String = canonicalConfig,
        hevPackagePorts: Map<String, Int> = emptyMap()
    ): RuntimeSnapshot? = runCatching {
        val source = JsonParser.parseString(canonicalConfig).asJsonObject
        val effective = JsonParser.parseString(effectiveConfig).asJsonObject
        val tun = source.getAsJsonArray("inbounds")?.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("type")?.asString == "tun"
        }?.asJsonObject
        requireNotNull(tun) { "缺少规范 TUN 配置，无法确认应用接管范围" }
        val include = strings(tun, "include_package").filterNot { it == "com.rr.client" }
        val exclude = strings(tun, "exclude_package").filterNot { it == "com.rr.client" }
        val rules = source.getAsJsonObject("route")?.getAsJsonArray("rules")
            ?.filter { it.isJsonObject }?.map { it.asJsonObject }.orEmpty()
        val bindings = linkedMapOf<String, String>()
        rules.forEach { rule ->
            // Domain/protocol conditions must not be presented as an application-wide binding.
            if (rule.keySet().any { it !in setOf("package_name", "action", "outbound") }) return@forEach
            val action = rule.get("action")?.asString
            val tag = rule.get("outbound")?.asString.orEmpty()
            val outlet = when {
                action == "reject" -> "reject"
                action == "route" && (tag == "proxy" || AppNodeRouting.nodeIdFromTag(tag) != null) -> outletKey(tag)
                else -> return@forEach
            }
            strings(rule, "package_name").filter { packageName.matches(it) }.forEach { name ->
                val captured = if (tun.has("include_package")) name in include else name !in exclude
                if (captured && name != "com.rr.client") bindings.putIfAbsent(name, outlet)
            }
        }
        val loaded = effective.getAsJsonArray("outbounds")?.mapNotNull {
            it.takeIf { value -> value.isJsonObject }?.asJsonObject?.get("tag")?.asString?.let(::outletKey)
        }?.toSet().orEmpty()
        val entrances = linkedMapOf<String, List<String>>()
        effective.getAsJsonArray("inbounds")?.forEach { element ->
            val inbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val tag = inbound.get("tag")?.asString.orEmpty()
            if (inbound.get("type")?.asString != "socks" || !tag.matches(Regex("hev-app-in-[0-9]+"))) return@forEach
            val port = inbound.get("listen_port")?.asInt ?: return@forEach
            val candidates = hevPackagePorts.filterValues { it == port }.keys
                .filter { packageName.matches(it) && it in bindings }.sorted()
            if (candidates.isNotEmpty()) entrances[tag] = candidates
        }
        RuntimeSnapshot(
            engine = engine.takeIf { it in setOf("SYSTEM", "HEV", "ROOT") } ?: "UNKNOWN",
            generation = generation,
            mainNode = nodeKey(mainNodeId),
            bindingVersion = digest(bindings.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }),
            scope = when {
                tun.has("include_package") -> "仅选中（${include.size} 个）"
                exclude.isNotEmpty() -> "选中绕过（${exclude.size} 个）"
                else -> "所有应用"
            },
            bindings = bindings.toMap(), loadedOutlets = loaded,
            detailEnabled = ConnectionRouteLog.enabledForConfig(effectiveConfig),
            unknownOwnerBlocked = rules.any(AppNodeRouting::isUnknownOwnerGuard),
            entrancePackages = entrances.toMap()
        )
    }.getOrNull()

    private fun strings(value: JsonObject?, key: String): List<String> = value?.getAsJsonArray(key)
        ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString }.orEmpty()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}

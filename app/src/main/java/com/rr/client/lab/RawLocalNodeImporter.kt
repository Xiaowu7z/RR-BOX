package com.rr.client.lab

import com.rr.client.RRApplication
import com.rr.client.core.ConfigBuilder
import com.rr.client.core.model.ProxyNode
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.subscription.SubscriptionParser
import com.rr.client.subscription.model.SubProfile
import io.nekohasekai.libbox.Libbox
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RawLocalImportResult(
    val parsed: Int,
    val added: Int,
    val duplicates: Int
) {
    fun message(): String = when {
        added > 0 -> "校验通过：$parsed/$parsed · 已加入 $added 个本地节点" +
            if (duplicates > 0) " · 跳过 $duplicates 个重复节点" else ""
        else -> "校验通过：$parsed/$parsed · 节点均已存在，没有重复写入"
    }
}

/** Strict all-or-nothing Raw importer used by Network Lab. */
object RawLocalNodeImporter {
    suspend fun import(raw: String): Result<RawLocalImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(raw.isNotBlank()) { "请输入 sing-box JSON" }

            val parsed = SubscriptionParser.parseContent(
                raw,
                SubProfile.LOCAL_PROFILE_ID,
                SubProfile.LOCAL_PROFILE_NAME
            )
            require(parsed.isNotEmpty()) {
                "未识别到 outbound；请检查 JSON 中是否包含可支持的 type/server/server_port"
            }
            require(parsed.size <= MAX_IMPORT_NODES) {
                "一次最多导入 $MAX_IMPORT_NODES 个 outbound"
            }

            val invalid = parsed.filterNot(::validateNode)
            require(invalid.isEmpty()) {
                val labels = invalid.take(3).joinToString { it.tag.ifBlank { it.type.name } }
                "有 ${invalid.size} 个 outbound 未通过 sing-box 1.14 校验" +
                    if (labels.isNotBlank()) "：$labels" else ""
            }

            val app = RRApplication.instance
            val profiles = app.database.profileDao().getAllProfiles().map(SubProfile::fromEntity)
            val existing = profiles.firstOrNull { it.isLocal }?.nodes.orEmpty()
            val identities = existing.map(::identity).toMutableSet()

            val additions = parsed.mapNotNull { candidate ->
                val normalized = candidate.copy(
                    id = "local-${UUID.randomUUID()}",
                    profileId = SubProfile.LOCAL_PROFILE_ID,
                    profileName = SubProfile.LOCAL_PROFILE_NAME
                )
                normalized.takeIf { identities.add(identity(it)) }
            }

            if (additions.isNotEmpty()) {
                app.database.profileDao().insertProfile(
                    SubProfile.local(existing + additions).toEntity()
                )
            }

            val result = RawLocalImportResult(
                parsed = parsed.size,
                added = additions.size,
                duplicates = parsed.size - additions.size
            )
            RRLogStore.record(
                "RAW",
                "Raw 一键导入: parsed=${result.parsed}, added=${result.added}, duplicates=${result.duplicates}"
            )
            result
        }
    }

    private fun validateNode(node: ProxyNode): Boolean = runCatching {
        val config = ConfigBuilder.buildSingBoxConfig(
            selectedNode = node,
            allNodes = listOf(node),
            appRoutes = emptyList(),
            smartRouting = false,
            perAppMode = PerAppPolicyResolver.MODE_ALL,
            fastForwarding = false
        )
        Libbox.checkConfig(config)
    }.isSuccess

    private fun identity(node: ProxyNode): String = node.rawJson.takeIf(String::isNotBlank)
        ?: "${node.type}|${node.server}|${node.serverPort}|${node.uuidOrPassword}|${node.extraPassword}"

    private const val MAX_IMPORT_NODES = 256
}

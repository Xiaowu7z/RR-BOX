package com.rr.client.subscription.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.ProfileEntity

/**
 * 内存态节点组（= ProfileEntity 的解析视图）。
 * 普通组来自远程订阅；LOCAL_PROFILE_ID 是 RRBOX 本地节点组，复用现有 Room 表，
 * 从而不需要数据库迁移也不会影响已安装稳定版的数据。
 */
data class SubProfile(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val nodes: List<ProxyNode>,
    val userInfo: SubscriptionUserInfo
) {
    val isLocal: Boolean
        get() = id == LOCAL_PROFILE_ID

    companion object {
        const val LOCAL_PROFILE_ID = "__rrbox_local_nodes__"
        const val LOCAL_PROFILE_NAME = "本地节点"
        const val LOCAL_PROFILE_URL = "local://rrbox"

        private val gson = Gson()

        fun fromEntity(entity: ProfileEntity): SubProfile {
            val nodes = try {
                val type = object : TypeToken<List<ProxyNode>>() {}.type
                gson.fromJson<List<ProxyNode>>(entity.nodesJson, type).orEmpty()
                    .map { it.copy(profileId = entity.id, profileName = entity.name) }
            } catch (e: Exception) {
                emptyList()
            }
            return SubProfile(
                id = entity.id,
                name = entity.name,
                url = entity.subscriptionUrl,
                lastUpdated = entity.lastUpdated,
                nodes = nodes,
                userInfo = SubscriptionUserInfo(
                    upload = entity.uploadBytes,
                    download = entity.downloadBytes,
                    total = entity.totalBytes,
                    expireTimestamp = entity.expireTime
                )
            )
        }

        fun local(nodes: List<ProxyNode>, lastUpdated: Long = System.currentTimeMillis()): SubProfile =
            SubProfile(
                id = LOCAL_PROFILE_ID,
                name = LOCAL_PROFILE_NAME,
                url = LOCAL_PROFILE_URL,
                lastUpdated = lastUpdated,
                nodes = nodes.map { it.copy(profileId = LOCAL_PROFILE_ID, profileName = LOCAL_PROFILE_NAME) },
                userInfo = SubscriptionUserInfo()
            )
    }

    fun toEntity(): ProfileEntity = ProfileEntity(
        id = id,
        name = name,
        subscriptionUrl = url,
        lastUpdated = lastUpdated,
        uploadBytes = userInfo.upload,
        downloadBytes = userInfo.download,
        totalBytes = userInfo.total,
        expireTime = userInfo.expireTimestamp,
        nodesJson = gson.toJson(nodes)
    )
}

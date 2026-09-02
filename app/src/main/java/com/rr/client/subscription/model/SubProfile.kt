package com.rr.client.subscription.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.ProfileEntity

/**
 * 内存态订阅组（= ProfileEntity 的解析视图），
 * 由 MainActivity 统一加载并在 Room 中持久化。
 */
data class SubProfile(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long,
    val nodes: List<ProxyNode>,
    val userInfo: SubscriptionUserInfo
) {
    companion object {
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

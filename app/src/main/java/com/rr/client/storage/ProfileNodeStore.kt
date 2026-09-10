package com.rr.client.storage

import androidx.room.withTransaction
import com.rr.client.subscription.model.SubProfile
import com.rr.client.subscription.TrafficInfoNode

/** Removes loaded nodes only; the next subscription refresh can load them again. */
object ProfileNodeStore {
    /** Check again after asynchronous preparation, before dispatching a user start. */
    suspend fun containsConnectableNode(database: AppDatabase, nodeId: String): Boolean =
        database.withTransaction {
            nodeId.isNotBlank() && database.profileDao().getAllProfiles().any { entity ->
                SubProfile.fromEntity(entity).nodes.any {
                    it.id == nodeId && !TrafficInfoNode.isInfoNode(it)
                }
            }
        }

    suspend fun remove(
        database: AppDatabase,
        profileId: String,
        nodeIds: Set<String>?,
        canRemove: (Set<String>) -> Boolean
    ): Set<String> = database.withTransaction {
        remove(database.profileDao(), profileId, nodeIds, canRemove)
    }

    // The caller holds the Room transaction while reading, checking, and writing.
    internal suspend fun remove(
        dao: ProfileDao,
        profileId: String,
        nodeIds: Set<String>?,
        canRemove: (Set<String>) -> Boolean
    ): Set<String> {
        val profile = dao.getAllProfiles().firstOrNull { it.id == profileId }
            ?.let(SubProfile::fromEntity) ?: return emptySet()
        val removedIds = profile.nodes.asSequence()
            .filter { nodeIds == null || it.id in nodeIds }
            .map { it.id }
            .toSet()
        if (removedIds.isEmpty()) return emptySet()
        check(canRemove(removedIds)) {
            "当前节点正在使用，请先断开或切换连接后再删除"
        }
        val nodesJson = profile.copy(nodes = profile.nodes.filterNot { it.id in removedIds })
            .toEntity().nodesJson
        dao.updateProfileNodes(profileId, nodesJson)
        return removedIds
    }
}

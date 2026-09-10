package com.rr.client.storage

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.SubscriptionNodeReconciler
import com.rr.client.subscription.model.SubProfile
import com.rr.client.subscription.model.SubscriptionUserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileNodeStoreTest {
    private val first = ProxyNode(
        "first", "First", ProtocolType.VLESS_TLS, "first.example", 443, "credential",
        profileId = "subscription", profileName = "My subscription"
    )
    private val second = first.copy(id = "second", tag = "Second", server = "second.example")
    private val subscription = SubProfile(
        id = "subscription", name = "My subscription",
        url = "https://subscription.example/path?token=a%2Bb", lastUpdated = 123456L,
        nodes = listOf(first, second),
        userInfo = SubscriptionUserInfo(upload = 11L, download = 22L, total = 999L, expireTimestamp = 5678L)
    )

    @Test fun removingOneNodePreservesOtherNodesAndEverySubscriptionField() = runBlocking {
        val original = subscription.toEntity()
        val dao = MemoryProfileDao(original)
        var checkedIds = emptySet<String>()

        val removed = ProfileNodeStore.remove(dao, subscription.id, setOf(first.id, "missing")) {
            checkedIds = it
            true
        }

        assertEquals(setOf(first.id), removed)
        assertEquals(removed, checkedIds)
        val stored = dao.entity(subscription.id)
        assertEquals(original.copy(nodesJson = stored.nodesJson), stored)
        assertEquals(subscription.copy(nodes = listOf(second)), SubProfile.fromEntity(stored))
        assertEquals(listOf(subscription.id), dao.nodeWriteIds)
    }

    @Test fun clearingGroupKeepsSubscriptionAndCannotTouchAnotherProfileWithSameNodeIds() = runBlocking {
        val original = subscription.toEntity()
        val other = subscription.copy(id = "other", name = "Other subscription").toEntity()
        val dao = MemoryProfileDao(original, other)

        val removed = ProfileNodeStore.remove(dao, subscription.id, null) { true }

        assertEquals(setOf(first.id, second.id), removed)
        val stored = dao.entity(subscription.id)
        assertEquals(original.copy(nodesJson = "[]"), stored)
        assertEquals(subscription.copy(nodes = emptyList()), SubProfile.fromEntity(stored))
        assertEquals(other, dao.entity(other.id))
        assertEquals(listOf(subscription.id), dao.nodeWriteIds)
    }

    @Test fun missingProfileMissingNodesAndEmptySelectionDoNotCheckOrWrite() = runBlocking {
        val original = subscription.toEntity()
        val dao = MemoryProfileDao(original)
        val unexpectedCheck: (Set<String>) -> Boolean = { error("No nodes should be checked") }

        assertTrue(ProfileNodeStore.remove(dao, "missing-profile", null, unexpectedCheck).isEmpty())
        assertTrue(ProfileNodeStore.remove(dao, subscription.id, setOf("missing-node"), unexpectedCheck).isEmpty())
        assertTrue(ProfileNodeStore.remove(dao, subscription.id, emptySet(), unexpectedCheck).isEmpty())

        assertTrue(dao.nodeWriteIds.isEmpty())
        assertEquals(original, dao.entity(subscription.id))
    }

    @Test fun blockedRemovalLeavesWholeGroupUnchanged() = runBlocking {
        val original = subscription.toEntity()
        val dao = MemoryProfileDao(original)

        try {
            ProfileNodeStore.remove(dao, subscription.id, null) { ids ->
                assertEquals(setOf(first.id, second.id), ids)
                false
            }
            fail("Active nodes must block the write")
        } catch (error: IllegalStateException) {
            assertEquals("当前节点正在使用，请先断开或切换连接后再删除", error.message)
        }

        assertTrue(dao.nodeWriteIds.isEmpty())
        assertEquals(original, dao.entity(subscription.id))
    }

    @Test fun currentStoredNodesAreUsedInsteadOfEarlierUiSnapshot() = runBlocking {
        val dao = MemoryProfileDao(subscription.toEntity())
        val newlyLoaded = first.copy(id = "new-node", server = "new.example")
        dao.insertProfile(subscription.copy(nodes = listOf(second, newlyLoaded), name = "Renamed").toEntity())

        val removed = ProfileNodeStore.remove(dao, subscription.id, null) { true }

        assertEquals(setOf(second.id, newlyLoaded.id), removed)
        assertEquals("Renamed", dao.entity(subscription.id).name)
    }

    @Test fun clearingLocalGroupPreservesLocalProfileIdentity() = runBlocking {
        val local = SubProfile.local(listOf(first, second), lastUpdated = 67890L)
        val dao = MemoryProfileDao(local.toEntity(), subscription.toEntity())

        assertEquals(setOf(first.id, second.id), ProfileNodeStore.remove(dao, local.id, null) { true })

        assertEquals(local.copy(nodes = emptyList()), SubProfile.fromEntity(dao.entity(local.id)))
        assertEquals(subscription.toEntity(), dao.entity(subscription.id))
    }

    @Test fun refreshingAfterOneNodeDeletionReloadsItAndRetainsSurvivingNodeIdentity() = runBlocking {
        val dao = MemoryProfileDao(subscription.toEntity())
        ProfileNodeStore.remove(dao, subscription.id, setOf(first.id)) { true }

        val refreshed = refresh(dao)

        assertEquals(listOf(first.server, second.server), refreshed.nodes.map { it.server })
        assertEquals(second.id, refreshed.nodes.last().id)
        assertNotEquals(first.id, refreshed.nodes.first().id)
        assertEquals(subscription.url, refreshed.url)
    }

    @Test fun refreshingAfterGroupClearReloadsAllNodes() = runBlocking {
        val dao = MemoryProfileDao(subscription.toEntity())
        ProfileNodeStore.remove(dao, subscription.id, null) { true }

        val refreshed = refresh(dao)

        assertEquals(listOf(first.server, second.server), refreshed.nodes.map { it.server })
        assertEquals(subscription.url, refreshed.url)
        assertEquals(subscription.name, refreshed.name)
    }

    private suspend fun refresh(dao: MemoryProfileDao): SubProfile {
        val stored = SubProfile.fromEntity(dao.entity(subscription.id))
        val incoming = listOf(first.copy(id = "incoming-1"), second.copy(id = "incoming-2"))
        val refreshed = stored.copy(
            nodes = SubscriptionNodeReconciler.reconcile(stored.nodes, incoming),
            lastUpdated = stored.lastUpdated + 1
        ).toEntity()
        dao.updateSubscriptionContent(
            refreshed.id, refreshed.nodesJson, refreshed.lastUpdated,
            refreshed.uploadBytes, refreshed.downloadBytes, refreshed.totalBytes, refreshed.expireTime
        )
        return SubProfile.fromEntity(dao.entity(subscription.id))
    }

    private class MemoryProfileDao(vararg initial: ProfileEntity) : ProfileDao {
        private val profiles = initial.associateBy { it.id }.toMutableMap()
        val nodeWriteIds = mutableListOf<String>()

        fun entity(id: String): ProfileEntity = profiles.getValue(id)

        override suspend fun getAllProfiles(): List<ProfileEntity> = profiles.values.toList()
        override fun observeProfiles(): Flow<List<ProfileEntity>> = flowOf(profiles.values.toList())
        override suspend fun insertProfile(profile: ProfileEntity) { profiles[profile.id] = profile }
        override suspend fun renameProfile(id: String, name: String): Int {
            val old = profiles[id] ?: return 0
            profiles[id] = old.copy(name = name)
            return 1
        }
        override suspend fun updateProfileNodes(id: String, nodesJson: String): Int {
            nodeWriteIds.add(id)
            val old = profiles[id] ?: return 0
            profiles[id] = old.copy(nodesJson = nodesJson)
            return 1
        }
        override suspend fun updateSubscriptionContent(
            id: String, nodesJson: String, lastUpdated: Long, uploadBytes: Long,
            downloadBytes: Long, totalBytes: Long, expireTime: Long
        ): Int {
            val old = profiles[id] ?: return 0
            profiles[id] = old.copy(
                nodesJson = nodesJson, lastUpdated = lastUpdated, uploadBytes = uploadBytes,
                downloadBytes = downloadBytes, totalBytes = totalBytes, expireTime = expireTime
            )
            return 1
        }
        override suspend fun deleteProfile(profile: ProfileEntity) { profiles.remove(profile.id) }
    }
}

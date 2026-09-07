package com.rr.client.subscription

import com.google.gson.Gson
import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.ProfileEntity
import com.rr.client.subscription.model.SubProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SubProfileRenameTest {
    @Test fun renamedProfileOverridesStaleEmbeddedNameWithoutChangingConnectionOrUrl() {
        val node = ProxyNode(
            id = "stable-node-id", tag = "original node tag", type = ProtocolType.VLESS_REALITY,
            server = "edge.example", serverPort = 2443, uuidOrPassword = "credential",
            flow = "xtls-rprx-vision", realityPublicKey = "public-key", realityShortId = "abcdef",
            sni = "sni.example", network = "ws", path = "/path?key=value", host = "cdn.example",
            alpn = "h2,http/1.1", allowInsecure = true, profileId = "profile-id", profileName = "old name",
            rawJson = """{"type":"vless","uuid":"credential","custom":{"preserve":"exact"}}"""
        )
        val entity = ProfileEntity(
            id = "profile-id", name = "新的订阅名称", subscriptionUrl = "https://sub.example/path?token=a%2Bb",
            lastUpdated = 1234L, uploadBytes = 11L, downloadBytes = 22L, totalBytes = 999L,
            expireTime = 5678L, nodesJson = Gson().toJson(listOf(node))
        )
        val profile = SubProfile.fromEntity(entity)
        assertEquals(entity.name, profile.name)
        assertEquals(entity.subscriptionUrl, profile.url)
        assertEquals(entity.lastUpdated, profile.lastUpdated)
        assertEquals(node.copy(profileName = entity.name), profile.nodes.single())
        assertEquals(11L, profile.userInfo.upload)
        assertEquals(22L, profile.userInfo.download)
        assertEquals(999L, profile.userInfo.total)
        assertEquals(5678L, profile.userInfo.expireTimestamp)
    }
}

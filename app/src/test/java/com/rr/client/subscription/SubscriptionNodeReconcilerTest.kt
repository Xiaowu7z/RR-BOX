package com.rr.client.subscription

import com.rr.client.core.model.ProtocolType
import com.rr.client.core.model.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class SubscriptionNodeReconcilerTest {
    private val a = ProxyNode("old-a", "A", ProtocolType.VLESS_TLS, "same.example.com", 443, "a", profileId = "p")
    private val b = a.copy(id = "old-b", tag = "B", uuidOrPassword = "b")
    @Test fun reorderedNodesKeepTheirOriginalIds() {
        val result = SubscriptionNodeReconciler.reconcile(listOf(a, b), listOf(b.copy(id="new-0"), a.copy(id="new-1")))
        assertEquals(listOf("old-b", "old-a"), result.map { it.id })
    }
    @Test fun uniqueCredentialRotationRetainsNameOverrideIdentity() {
        val result = SubscriptionNodeReconciler.reconcile(listOf(a), listOf(a.copy(uuidOrPassword="rotated")))
        assertEquals("old-a", result.single().id)
        assertEquals("rotated", result.single().uuidOrPassword)
    }
    @Test fun remoteNameChangeWithIdenticalEndpointKeepsId() {
        assertEquals("old-a", SubscriptionNodeReconciler.reconcile(listOf(a), listOf(a.copy(tag="Renamed"))).single().id)
    }
    @Test fun unmatchedNodeCannotReuseOldParserId() {
        val result = SubscriptionNodeReconciler.reconcile(listOf(a), listOf(a.copy(server="different.example.com")))
        assertNotEquals(a.id, result.single().id)
    }
    @Test fun ambiguousDuplicateNamesNeverReceiveAnArbitraryOldOverride() {
        val second = a.copy(id="old-b", uuidOrPassword="b")
        val incoming = listOf(a.copy(uuidOrPassword="c"), second.copy(uuidOrPassword="d"))
        val ids = SubscriptionNodeReconciler.reconcile(listOf(a, second), incoming).map { it.id }
        assertEquals(2, ids.toSet().size)
        assertFalse(ids.any { it == "old-a" || it == "old-b" })
    }

    @Test fun exactMatchCannotBeStolenByAnEarlierFallback() {
        val a = this.a.copy(tag = "x", uuidOrPassword = "one")
        val b = this.b.copy(tag = "y", uuidOrPassword = "two")
        val result = SubscriptionNodeReconciler.reconcile(listOf(a, b), listOf(
            b.copy(id = "new-b", tag = "x", uuidOrPassword = "rotated"),
            a.copy(id = "new-a", tag = "y")
        ))
        assertEquals("old-a", result[1].id)
        assertNotEquals("old-a", result[0].id)
    }
}

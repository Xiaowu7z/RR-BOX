package com.rr.client.subscription

import com.rr.client.core.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCapabilityRegistryTest {
    @Test
    fun everyProtocolTypeHasCapabilityEntry() {
        val types = ProtocolType.values().toSet()
        val registered = ProtocolCapabilityRegistry.entries.map { it.type }.toSet()
        assertEquals(types, registered)
    }

    @Test
    fun anyTlsAndNaiveKeepShareAndRawImport() {
        listOf(
            ProtocolType.ANYTLS,
            ProtocolType.NAIVE_H2,
            ProtocolType.NAIVE_H3
        ).forEach { type ->
            val capability = ProtocolCapabilityRegistry.capability(type)
            assertTrue("$type share-link import", capability.shareLinkImport)
            assertTrue("$type raw JSON import", capability.rawJsonImport)
        }
    }

    @Test
    fun wireGuardAndTorAreDeclaredRawOnly() {
        listOf(ProtocolType.WIREGUARD, ProtocolType.TOR).forEach { type ->
            val capability = ProtocolCapabilityRegistry.capability(type)
            assertFalse(capability.shareLinkImport)
            assertTrue(capability.rawJsonImport)
        }
    }
}

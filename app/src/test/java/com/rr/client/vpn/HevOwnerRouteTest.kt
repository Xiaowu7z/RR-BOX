package com.rr.client.vpn

import org.junit.Assert.*
import org.junit.Test

class HevOwnerRouteTest {
    @Test
    fun routeIdentityPreservesUidEvenWhenTwoOwnersUseTheDefaultPort() {
        val first = HevOwnerRoute.pack(10001, 20808)
        val second = HevOwnerRoute.pack(10002, 20808)
        assertNotEquals(first, second)
        assertEquals(10001L, first ushr 32)
        assertEquals(20808L, first and 0xffffL)
        assertTrue(first > 0)
        assertTrue(HevOwnerRoute.pack(0, 20808) > 0)
    }

    @Test
    fun packageRefreshGenerationsPreventUidReuseFromReusingAnOldAssociation() {
        val old = HevOwnerRoute.pack(10001, 20809, 1)
        val refreshed = HevOwnerRoute.pack(10001, 20809, 2)
        assertNotEquals(old, refreshed)
        assertEquals(old ushr 32, refreshed ushr 32)
        assertEquals(old and 0xffffL, refreshed and 0xffffL)
        assertEquals(2L, (refreshed ushr 16) and 0xffffL)
    }

    @Test
    fun invalidOwnerOrPortCannotBecomeAnUnrestrictedLegacyRoute() {
        assertThrows(IllegalArgumentException::class.java) { HevOwnerRoute.pack(-1, 20808) }
        assertThrows(IllegalArgumentException::class.java) { HevOwnerRoute.pack(10001, 0) }
        assertThrows(IllegalArgumentException::class.java) { HevOwnerRoute.pack(10001, 65536) }
    }
}

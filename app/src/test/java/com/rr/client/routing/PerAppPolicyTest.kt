package com.rr.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppPolicyTest {
    private val self = "com.rr.client"

    @Test
    fun allModeExcludesOnlySelf() {
        val result = PerAppPolicyResolver.resolve(
            PerAppPolicyResolver.MODE_ALL,
            setOf("org.telegram.messenger"),
            self
        )
        assertTrue(result.allowedPackages.isEmpty())
        assertEquals(listOf(self), result.disallowedPackages)
    }

    @Test
    fun allowListAllowsOnlySelectedAppsAndNeverSelf() {
        val result = PerAppPolicyResolver.resolve(
            PerAppPolicyResolver.MODE_ALLOW_LIST,
            setOf("org.telegram.messenger", self, "com.twitter.android"),
            self
        )
        assertEquals(
            listOf("com.twitter.android", "org.telegram.messenger"),
            result.allowedPackages
        )
        assertTrue(result.disallowedPackages.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyAllowListIsRejected() {
        PerAppPolicyResolver.resolve(
            PerAppPolicyResolver.MODE_ALLOW_LIST,
            emptySet(),
            self
        )
    }

    @Test
    fun bypassModeExcludesSelectedAppsAndSelf() {
        val result = PerAppPolicyResolver.resolve(
            PerAppPolicyResolver.MODE_DISALLOW_LIST,
            setOf("com.example.direct"),
            self
        )
        assertTrue(result.allowedPackages.isEmpty())
        assertEquals(setOf("com.example.direct", self), result.disallowedPackages.toSet())
    }
}

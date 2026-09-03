package com.rr.client.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun comparesReleaseTagsAgainstBuildNames() {
        assertTrue(AppUpdateChecker.compareVersions("v0.1.8", "0.1.7-smart-routing") > 0)
        assertTrue(AppUpdateChecker.compareVersions("0.2.0", "0.1.99") > 0)
        assertEquals(0, AppUpdateChecker.compareVersions("v0.1.7", "0.1.7-smart-routing"))
    }
}

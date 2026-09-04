package com.rr.client.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun comparesReleaseTagsAgainstBuildNames() {
        assertTrue(AppUpdateChecker.compareVersions("v0.9.4", "0.9.3") > 0)
        assertTrue(AppUpdateChecker.compareVersions("0.10.0", "0.9.99") > 0)
        assertEquals(0, AppUpdateChecker.compareVersions("v0.9.4", "0.9.4-hotfix"))
    }

    @Test
    fun acceptsOnlyOfficialArm64ReleaseApk() {
        assertTrue(AppUpdateChecker.isSupportedApkAsset("RRBOX-0.9.4-arm64-v8a.apk"))
        assertFalse(AppUpdateChecker.isSupportedApkAsset("RRBOX-0.9.4-x86_64.apk"))
        assertFalse(AppUpdateChecker.isSupportedApkAsset("other-0.9.4-arm64-v8a.apk"))
        assertFalse(AppUpdateChecker.isSupportedApkAsset("RRBOX-0.9.4-arm64-v8a.zip"))
    }
}

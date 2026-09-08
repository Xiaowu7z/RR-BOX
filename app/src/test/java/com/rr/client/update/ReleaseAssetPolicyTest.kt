package com.rr.client.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseAssetPolicyTest {
    private val name = "RRBOX-1.0.2-arm64-v8a.apk"
    private val good = "https://github.com/Xiaowu7z/RR-BOX/releases/download/v1.0.2/$name"
    @Test fun acceptsOfficialReleaseAsset() { assertTrue(ReleaseAssetPolicy.isTrustedDownload(good, name)) }
    @Test fun rejectsForeignHostsAndUserInfo() {
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good.replace("github.com", "evil.example"), name))
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good.replace("github.com", "github.com@evil.example"), name))
    }
    @Test fun rejectsOtherRepositoriesAndSchemes() {
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good.replace("RR-BOX/", "another/"), name))
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good.replace("https:", "http:"), name))
    }
    @Test fun rejectsQueriesAndMismatchedFilenames() {
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good+"?redirect=1", name))
        assertFalse(ReleaseAssetPolicy.isTrustedDownload(good+".html", name))
    }
}

package com.rr.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoProxySelectionPolicyTest {
    @Test
    fun selectsInstalledCommonInternationalAppsOnly() {
        val recommended = setOf(
            "com.openai.chatgpt", "org.telegram.messenger", "com.twitter.android",
            "com.google.android.youtube", "com.github.android", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.rezvorck.tiktokplugin", "sg.bigo.live"
        )

        assertEquals(
            recommended,
            AutoProxySelectionPolicy.select(
                installedPackages = recommended + "com.example.unreviewed",
                currentSelection = emptySet(),
                excludedPackages = emptySet()
            )
        )
    }

    @Test
    fun neverGuessesDomesticAppsOrRepackagedIdentities() {
        val unrecognized = setOf(
            "com.tencent.mm", "com.tencent.mobileqq", "com.ss.android.ugc.aweme",
            "com.eg.android.AlipayGphone", "com.taobao.taobao", "tv.danmaku.bili",
            "com.wuge.xiaowu", "com.example.ChatGPT", "com.openai.chatgpt.clone",
            "org.telegram.messenger.clone", "com.twitter.android.lite",
            "com.google.android.unreviewed", "com.rr.client"
        )

        assertTrue(
            AutoProxySelectionPolicy.select(unrecognized, emptySet(), emptySet()).isEmpty()
        )
    }

    @Test
    fun preservesInstalledManualChoicesAndDropsUninstalledPackages() {
        assertEquals(
            setOf("com.example.manual", "com.tencent.mm", "com.openai.chatgpt"),
            AutoProxySelectionPolicy.select(
                installedPackages = setOf(
                    "com.example.manual", "com.tencent.mm", "com.openai.chatgpt"
                ),
                currentSelection = setOf(
                    "com.example.manual", "com.tencent.mm", "com.example.uninstalled",
                    "org.telegram.messenger", "com.rr.client"
                ),
                excludedPackages = emptySet()
            )
        )
    }

    @Test
    fun explicitCancellationWinsOverRecommendationsAndStaleSelections() {
        val installed = setOf("com.openai.chatgpt", "com.twitter.android", "com.example.manual")

        assertEquals(
            setOf("com.twitter.android"),
            AutoProxySelectionPolicy.select(
                installedPackages = installed,
                currentSelection = setOf("com.openai.chatgpt", "com.example.manual"),
                excludedPackages = setOf("com.openai.chatgpt", "com.example.manual")
            )
        )
    }

    @Test
    fun includesExactGoogleSystemServicesWithoutMatchingOtherSystemApps() {
        val googleServices = setOf(
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.gsf.login", "com.google.android.googlequicksearchbox",
            "com.google.android.apps.bard"
        )
        val otherSystemApps = setOf(
            "com.android.providers.downloads", "com.android.systemui", "com.android.settings",
            "com.google.android.webview", "com.google.android.inputmethod.latin",
            "com.google.android.gms.clone"
        )

        assertEquals(
            googleServices,
            AutoProxySelectionPolicy.select(
                googleServices + otherSystemApps, emptySet(), emptySet()
            )
        )
    }

    @Test
    fun selectsGoogleBackgroundPushAndAccountServicesWithTheForegroundApp() {
        val installed = setOf(
            "com.openai.chatgpt", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.gsf.login", "com.android.vending"
        )

        assertEquals(installed, AutoProxySelectionPolicy.select(installed, emptySet(), emptySet()))
        assertEquals(
            installed - "com.google.android.gms",
            AutoProxySelectionPolicy.select(
                installed, installed, setOf("com.google.android.gms")
            )
        )
    }

    @Test
    fun selectsKnownTelegramClientsGrokAndBrowsersByTheirExactIdentities() {
        val expected = setOf(
            "com.radolyn.ayugram", "com.iMe.android", "xyz.nextalone.nagram",
            "ai.x.grok", "com.android.chrome", "com.microsoft.emmx"
        )
        val lookalikes = setOf(
            "com.radolyn.ayugram.clone", "com.ime.android", "com.IME.android",
            "xyz.nextalone.Nagram", "ai.x.grok.clone", "com.android.Chrome",
            "com.microsoft.emmx.clone"
        )

        assertEquals(
            expected,
            AutoProxySelectionPolicy.select(expected + lookalikes, emptySet(), emptySet())
        )
    }

    @Test
    fun selectsGoogleCloudAppsWithoutBroadlySelectingGooglePackages() {
        val expected = setOf(
            "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets",
            "com.google.android.apps.docs.editors.slides",
            "com.google.android.apps.tasks", "com.google.android.keep",
            "com.google.android.calendar", "com.google.android.contacts",
            "com.google.android.apps.googleassistant", "com.google.android.play.games",
            "com.google.android.apps.authenticator2"
        )
        val notRecommended = setOf(
            "com.google.android.unreviewed", "com.google.android.apps.unreviewed",
            "com.google.android.calculator", "com.google.android.deskclock",
            "com.google.android.apps.nbu.files", "com.google.android.safetycore",
            "com.google.android.apps.tasks.clone"
        )

        assertEquals(
            expected,
            AutoProxySelectionPolicy.select(expected + notRecommended, emptySet(), emptySet())
        )
    }

    @Test
    fun selectsKnownInternationalStoresAndAppUpdateClients() {
        val expected = setOf(
            "com.vkontakte.android", "com.valvesoftware.android.steam.community",
            "org.fdroid.fdroid", "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid"
        )

        assertEquals(
            expected,
            AutoProxySelectionPolicy.select(
                expected + "dev.imranr.obtainium.clone", emptySet(), emptySet()
            )
        )
    }

    @Test
    fun homeAndWalletStayManualButInstalledUserChoicesArePreserved() {
        val manualApps = setOf(
            "com.google.android.apps.chromecast.app", "com.google.android.apps.walletnfcrel"
        )

        assertTrue(AutoProxySelectionPolicy.select(manualApps, emptySet(), emptySet()).isEmpty())
        assertEquals(
            manualApps,
            AutoProxySelectionPolicy.select(manualApps, manualApps, emptySet())
        )
        assertEquals(
            setOf("com.google.android.apps.chromecast.app"),
            AutoProxySelectionPolicy.select(
                manualApps, manualApps, setOf("com.google.android.apps.walletnfcrel")
            )
        )
    }

    @Test
    fun extraGroupsSupplementCatalogueAndStillRespectInstallationAndExclusions() {
        val groups = listOf(
            listOf("com.example.extra", "com.example.uninstalled", "com.rr.client", ""),
            listOf("com.example.extra", "com.example.excluded")
        )

        assertEquals(
            setOf("com.openai.chatgpt", "com.example.extra"),
            AutoProxySelectionPolicy.select(
                installedPackages = setOf(
                    "com.openai.chatgpt", "com.example.extra", "com.example.excluded",
                    "com.example.extra.clone", "com.rr.client"
                ),
                currentSelection = setOf("com.rr.client"),
                excludedPackages = setOf("com.example.excluded"),
                extraPackageGroups = groups
            )
        )
        val recommendations = AutoProxySelectionPolicy.recommendedPackages(groups)
        assertTrue("com.openai.chatgpt" in recommendations)
        assertTrue("com.example.extra" in recommendations)
        assertFalse("com.rr.client" in recommendations)
        assertFalse("" in recommendations)
    }

    @Test
    fun noMatchesAndAllRecommendationsCancelledStayEmpty() {
        assertTrue(
            AutoProxySelectionPolicy.select(
                setOf("com.example.unknown"), setOf("com.example.uninstalled"), emptySet()
            ).isEmpty()
        )
        assertTrue(
            AutoProxySelectionPolicy.select(
                setOf("com.openai.chatgpt", "com.tencent.mm"), emptySet(),
                setOf("com.openai.chatgpt")
            ).isEmpty()
        )
        assertTrue(
            AutoProxySelectionPolicy.select(
                emptySet(), setOf("com.openai.chatgpt"), emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun refreshingSelectionKeepsManualCancellationAndManualAddition() {
        val installed = setOf("com.openai.chatgpt", "com.twitter.android", "com.example.manual")
        val initial = AutoProxySelectionPolicy.select(installed, emptySet(), emptySet())
        val edited = (initial - "com.twitter.android") + "com.example.manual"

        assertEquals(
            setOf("com.openai.chatgpt", "com.example.manual"),
            AutoProxySelectionPolicy.select(installed, edited, setOf("com.twitter.android"))
        )
    }
}

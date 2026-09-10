package com.rr.client.routing

/**
 * Exact app identities used to suggest the selected-app allow list.
 * This changes selection only; it does not make an app's destinations force-proxy rules.
 * Unknown apps and repackaged clients remain a manual choice.
 */
object AutoProxySelectionPolicy {
    private const val SELF_PACKAGE = "com.rr.client"

    private val knownPackages = setOf(
        "com.openai.chatgpt",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram", // Telegram X
        "com.twitter.android",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",

        // Google Play and its own services can be system apps without launcher icons.
        // Package identities: developers.google.com/android/reference/com/google/android/gms/common/GooglePlayServicesUtil
        // GSF/account manager: knowledge.workspace.google.com/admin/devices/manage-system-apps-on-company-owned-mobile-devices
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.gsf.login",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard", // Gemini
        "com.google.android.gm",
        "com.google.android.apps.maps",
        "com.google.android.apps.docs", // Drive
        "com.google.android.apps.photos",

        "com.facebook.katana",
        "com.facebook.orca", // Messenger
        "com.instagram.android",
        "com.instagram.barcelona", // Threads
        "com.whatsapp",
        "com.discord",
        "com.reddit.frontpage",
        "com.github.android",
        "com.zhiliaoapp.musically", // International TikTok; never domestic Douyin
        "com.rezvorck.tiktokplugin",
        "sg.bigo.live"
    )

    /** Downloaded package groups only supplement the built-in selection catalogue. */
    fun recommendedPackages(extraPackageGroups: List<List<String>> = emptyList()): Set<String> =
        (knownPackages + extraPackageGroups.flatten())
            .filterTo(linkedSetOf()) { it.isNotBlank() && it != SELF_PACKAGE }

    /**
     * Keep installed manual choices, add installed recommendations, and honor explicit
     * exclusions even when stale current selections still contain those packages.
     * An empty result stays empty: callers must never interpret it as "all apps".
     */
    fun select(
        installedPackages: Set<String>,
        currentSelection: Set<String>,
        excludedPackages: Set<String>,
        extraPackageGroups: List<List<String>> = emptyList()
    ): Set<String> = (currentSelection + recommendedPackages(extraPackageGroups))
        .filterTo(linkedSetOf()) {
            it in installedPackages && it !in excludedPackages &&
                it.isNotBlank() && it != SELF_PACKAGE
        }
}

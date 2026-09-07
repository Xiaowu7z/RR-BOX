package com.rr.client.routing

/** Exact identities complement domain rules when Android can identify the connection owner. */
object TikTokAppPolicy {
    // Keep domestic Douyin (com.ss.android.ugc.aweme) outside this policy.
    // Other patched clients still receive the international domain policy.
    val proxyPackages = listOf("com.zhiliaoapp.musically", "com.rezvorck.tiktokplugin")
}

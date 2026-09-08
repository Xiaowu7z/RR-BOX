package com.rr.client.routing

/** Exact identity complements domain rules when Android can identify the connection owner. */
object BigoAppPolicy {
    // Official BIGO LIVE package; no package-prefix or neighboring-app matches.
    // https://play.google.com/store/apps/details?id=sg.bigo.live
    val proxyPackages = listOf("sg.bigo.live")
}

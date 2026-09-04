package com.rr.client.vpn

import android.content.Context

/**
 * Persists whether the user currently expects RRBOX to stay connected.
 *
 * This is intentionally separate from VpnRuntimeStateStore: the runtime cache must survive a
 * manual disconnect so the Quick Settings tile can reconnect instantly, while continuity recovery
 * must never reconnect after the user explicitly turned the VPN off.
 */
object VpnConnectionIntentStore {
    private const val PREFS = "rr_vpn_connection_intent"
    private const val KEY_DESIRED_RUNNING = "desired_running"

    fun setDesiredRunning(context: Context, desired: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DESIRED_RUNNING, desired)
            .apply()
    }

    fun isDesiredRunning(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED_RUNNING, false)
}

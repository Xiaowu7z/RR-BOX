package com.rr.client.vpn

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.core.content.IntentCompat
import com.rr.client.MainActivity

/** SystemUI's long-press entry. MainActivity still owns navigation and the normal PIN gate. */
class RRQuickTilePreferencesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOwnTilePreferencesIntent()) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = ACTION_OPEN_DASHBOARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }

    private fun isOwnTilePreferencesIntent(): Boolean = runCatching {
        if (intent.action != TileService.ACTION_QS_TILE_PREFERENCES) return@runCatching false
        // EXTRA_COMPONENT_NAME is optional; if supplied, do not accept another tile's request.
        if (!intent.hasExtra(Intent.EXTRA_COMPONENT_NAME)) return@runCatching true
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java) ==
            ComponentName(this, RRQuickTileService::class.java)
    }.getOrDefault(false)

    companion object {
        // This requests a screen only: it never grants access or starts/stops the VPN.
        const val ACTION_OPEN_DASHBOARD = "com.rr.client.action.OPEN_DASHBOARD"
    }
}

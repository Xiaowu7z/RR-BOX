package com.rr.client.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RRQuickTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastClickAt = 0L

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        val now = SystemClock.elapsedRealtime()
        if (now - lastClickAt < 300L) return
        lastClickAt = now

        if (RRVpnService.isRunning.value || RRVpnService.isStarting.value) {
            // Optimistic UI first: the user sees OFF immediately while the service tears down in background.
            setTileState(Tile.STATE_INACTIVE, "已断开")
            RRQuickTileController.stop(this)
            scope.launch {
                repeat(40) {
                    if (!RRVpnService.isRunning.value && !RRVpnService.isStarting.value) return@launch
                    delay(50L)
                }
                if (RRVpnService.isRunning.value) {
                    setTileState(Tile.STATE_ACTIVE, "已连接")
                }
            }
            return
        }

        if (VpnService.prepare(this) != null) {
            setTileState(Tile.STATE_INACTIVE, "需要 VPN 授权")
            startActivityAndCollapse(
                Intent(this, RRQuickTilePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }

        // Match mature VPN tiles: visual feedback is immediate; config/service work happens after it.
        setTileState(Tile.STATE_ACTIVE, "正在连接")
        scope.launch {
            val result = RRQuickTileController.connect(this@RRQuickTileService)
            result.onFailure { error ->
                setTileState(Tile.STATE_INACTIVE, "连接失败")
                Toast.makeText(
                    this@RRQuickTileService,
                    "快速连接失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            repeat(60) {
                when {
                    RRVpnService.isRunning.value -> {
                        setTileState(Tile.STATE_ACTIVE, "已连接")
                        return@launch
                    }
                    !RRVpnService.isStarting.value && !RRVpnService.lastError.value.isNullOrBlank() -> {
                        setTileState(Tile.STATE_INACTIVE, "连接失败")
                        return@launch
                    }
                }
                delay(50L)
            }
            refreshTile()
        }
    }

    private fun refreshTile() {
        val running = RRVpnService.isRunning.value
        val starting = RRVpnService.isStarting.value
        when {
            running -> setTileState(Tile.STATE_ACTIVE, "已连接")
            starting -> setTileState(Tile.STATE_ACTIVE, "正在连接")
            else -> setTileState(Tile.STATE_INACTIVE, "已断开")
        }
    }

    private fun setTileState(state: Int, subtitle: String) {
        qsTile?.let { tile ->
            tile.label = "RRBOX"
            tile.state = state
            tile.contentDescription = "RRBOX VPN · $subtitle"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = subtitle
            }
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

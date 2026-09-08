package com.rr.client.vpn

import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.rr.client.RRApplication
import com.rr.client.storage.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RRQuickTilePermissionActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        checkPermissionAndConnect(permissionResult = result.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) checkPermissionAndConnect()
    }

    private fun checkPermissionAndConnect(permissionResult: Int? = null) {
        lifecycleScope.launch {
            // Re-read selection even after the permission result: Root never needs Android VPN consent.
            val rootMode = RRApplication.instance.preferencesManager.tunEngine.first() ==
                PreferencesManager.TUN_ENGINE_ROOT ||
                RRVpnService.activeRuntimeEngine.value == PreferencesManager.TUN_ENGINE_ROOT ||
                RRVpnService.hasRootDataPlane()
            if (!rootMode) {
                val permissionIntent = VpnService.prepare(this@RRQuickTilePermissionActivity)
                if (permissionIntent != null) {
                    if (permissionResult == null) {
                        permissionLauncher.launch(permissionIntent)
                    } else {
                        Toast.makeText(this@RRQuickTilePermissionActivity, "VPN 授权未通过", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@launch
                }
            }
            RRQuickTileController.connect(this@RRQuickTilePermissionActivity)
                .onFailure { error ->
                    Toast.makeText(
                        this@RRQuickTilePermissionActivity,
                        "快速连接失败：${error.message ?: error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            finish()
        }
    }
}

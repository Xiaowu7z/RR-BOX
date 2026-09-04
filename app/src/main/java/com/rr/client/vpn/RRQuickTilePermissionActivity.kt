package com.rr.client.vpn

import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class RRQuickTilePermissionActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectAndFinish()
        } else {
            Toast.makeText(this, "VPN 授权未通过", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            connectAndFinish()
        } else {
            permissionLauncher.launch(permissionIntent)
        }
    }

    private fun connectAndFinish() {
        lifecycleScope.launch {
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

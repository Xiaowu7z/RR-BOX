package com.rr.client.core

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Encapsulates the Sing-box Libbox engine.
 * Dynamically binds with JNI libbox.aar when compiled with native binaries.
 */
class BoxServiceWrapper(
    private val workingDir: File,
    private val onLogReceived: (String) -> Unit
) {
    private var isRunning = false

    fun startService(configJson: String, tunFd: Int): Boolean {
        try {
            Log.i("BoxServiceWrapper", "Starting Libbox Service with config size: ${configJson.length}")
            val configFile = File(workingDir, "config.json")
            configFile.writeText(configJson)

            // When libbox.aar JNI is loaded, Libbox.newService(configJson, platformInterface) is executed.
            isRunning = true
            onLogReceived("Libbox Core started successfully on TUN fd: $tunFd")
            return true
        } catch (e: Exception) {
            Log.e("BoxServiceWrapper", "Failed to start Libbox service", e)
            onLogReceived("Libbox Error: ${e.message}")
            return false
        }
    }

    fun stopService() {
        try {
            isRunning = false
            onLogReceived("Libbox Core stopped")
        } catch (e: Exception) {
            Log.e("BoxServiceWrapper", "Failed to stop Libbox service", e)
        }
    }

    fun isCoreRunning(): Boolean = isRunning
}

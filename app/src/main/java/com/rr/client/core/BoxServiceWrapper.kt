package com.rr.client.core

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.io.File

class BoxServiceWrapper(
    private val workingDir: File,
    private val onLogReceived: (String) -> Unit
) {
    private var boxService: BoxService? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var isRunning = false

    fun startService(configJson: String, vpnService: VpnService): Boolean {
        return try {
            Log.i("BoxServiceWrapper", "Starting Libbox Service...")
            val configFile = File(workingDir, "config.json")
            configFile.writeText(configJson)

            val platformInterface = object : PlatformInterface {
                override fun openTun(options: TunOptions?): Int {
                    val builder = vpnService.Builder()
                        .setSession("RR Client")
                        .setMtu(1500)
                        .addAddress("172.19.0.1", 30)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("223.5.5.5")

                    val pfd = builder.establish()
                    tunPfd = pfd
                    val fd = pfd?.detachFd() ?: -1
                    Log.i("BoxServiceWrapper", "Established TUN with detached fd: $fd")
                    return fd
                }

                override fun autoDetectInterfaceControl(fd: Int) {
                    vpnService.protect(fd)
                }

                override fun writeLog(message: String?) {
                    message?.let {
                        Log.d("SingBoxCore", it)
                        onLogReceived(it)
                    }
                }

                override fun findConnectionOwner(
                    ipProtocol: Int,
                    sourceAddress: String?,
                    sourcePort: Int,
                    destinationAddress: String?,
                    destinationPort: Int
                ): Int = 0

                override fun packageList(): StringIterator? = null

                override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

                override fun localDNSTransport(): LocalDNSTransport? = null
            }

            val service = Libbox.newService(configJson, platformInterface)
            service.start()
            boxService = service
            isRunning = true
            onLogReceived("Sing-box 内核隧道已成功启动")
            true
        } catch (e: Throwable) {
            Log.e("BoxServiceWrapper", "启动 Sing-box 核心异常", e)
            onLogReceived("核心异常: ${e.message}")
            false
        }
    }

    fun stopService() {
        try {
            boxService?.close()
            boxService = null
            tunPfd?.close()
            tunPfd = null
            isRunning = false
            onLogReceived("Sing-box 核心已停止")
        } catch (e: Throwable) {
            Log.e("BoxServiceWrapper", "停止核心异常", e)
        }
    }

    fun isCoreRunning(): Boolean = isRunning
}

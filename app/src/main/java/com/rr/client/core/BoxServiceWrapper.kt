package com.rr.client.core

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.io.File

class BoxServiceWrapper(
    private val workingDir: File,
    private val onLogReceived: (String) -> Unit,
    private val onStatusUpdate: (StatusMessage) -> Unit
) : PlatformInterface, CommandServerHandler, CommandClientHandler {

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var boxService: BoxService? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var vpnService: VpnService? = null
    private var isRunning = false

    fun startService(configJson: String, vpn: VpnService): Boolean {
        return try {
            vpnService = vpn
            val configFile = File(workingDir, "config.json")
            configFile.writeText(configJson)

            // 1. 启动官方 CommandServer
            val server = Libbox.newCommandServer(this, 100)
            server.start()
            commandServer = server

            // 2. 启动官方 BoxService 内核
            val service = Libbox.newService(configJson, this)
            service.start()
            server.setService(service)
            boxService = service

            // 3. 启动 CommandClient 获取官方真实底层流量统计 (每秒推送)
            val clientOptions = CommandClientOptions().apply {
                command = Libbox.CommandStatus
                statusInterval = 1000L
            }
            val client = Libbox.newCommandClient(this, clientOptions)
            client.connect()
            commandClient = client

            isRunning = true
            onLogReceived("Sing-box 官方内核服务及 CommandClient 状态监听器已启动")
            true
        } catch (e: Throwable) {
            Log.e("BoxServiceWrapper", "Failed to start box service", e)
            onLogReceived("启动失败: ${e.message}")
            stopService()
            false
        }
    }

    fun stopService() {
        try {
            commandClient?.disconnect()
            commandClient = null
            commandServer?.close()
            commandServer = null
            boxService?.close()
            boxService = null
            tunPfd?.close()
            tunPfd = null
            vpnService = null
            isRunning = false
        } catch (e: Throwable) {
            Log.e("BoxServiceWrapper", "Error stopping service", e)
        }
    }

    // --- io.nekohasekai.libbox.PlatformInterface ---
    override fun openTun(options: TunOptions): Int {
        val vpn = vpnService ?: return -1
        val builder = vpn.Builder()
            .setSession("RR Client")
            .setMtu(options.mtu)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("223.5.5.5")

        val pfd = builder.establish()
        tunPfd = pfd
        return pfd?.detachFd() ?: -1
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        vpnService?.protect(fd)
    }

    override fun writeLog(message: String) {
        onLogReceived(message)
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): Int = 0

    override fun packageList(): StringIterator? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun localDNSTransport(): LocalDNSTransport? = null

    // --- io.nekohasekai.libbox.CommandServerHandler ---
    override fun serviceStop() {}
    override fun serviceReload() {}

    // --- io.nekohasekai.libbox.CommandClientHandler (真实实时流量回调) ---
    override fun connected() {
        Log.i("BoxServiceWrapper", "CommandClient connected to sing-box core")
    }

    override fun disconnected(message: String?) {
        Log.i("BoxServiceWrapper", "CommandClient disconnected: $message")
    }

    override fun writeStatus(message: StatusMessage) {
        onStatusUpdate(message)
    }

    fun isCoreRunning(): Boolean = isRunning
}

package com.rr.client.core

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

class BoxServiceWrapper(
    private val workingDir: File,
    private val onLogReceived: (String) -> Unit,
    private val onStatusUpdate: (StatusMessage) -> Unit
) : PlatformInterface, CommandServerHandler, CommandClientHandler {

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var vpnService: VpnService? = null
    private var lastConfigJson: String? = null
    private var isRunning = false
    private var isStopping = false

    /** 最近的内核/运行日志（错误诊断用） */
    private val recentLogs = ArrayDeque<String>()

    /** 最近一次启动失败的详细原因（供 UI 展示） */
    @Volatile
    var lastError: String? = null
        private set

    private fun recordLog(line: String) {
        synchronized(recentLogs) {
            recentLogs.addLast(line)
            while (recentLogs.size > 12) recentLogs.removeFirst()
        }
        onLogReceived(line)
    }

    private fun failWith(message: String?): Boolean {
        val tail = synchronized(recentLogs) {
            recentLogs.takeLast(6).filter { it.isNotBlank() }.joinToString("\n")
        }
        lastError = listOfNotNull(message?.takeIf { it.isNotBlank() }, tail.takeIf { it.isNotBlank() })
            .joinToString("\n").ifBlank { "未知错误" }
        Log.e(TAG, "sing-box start failed: $lastError")
        return false
    }

    fun startService(configJson: String, vpn: VpnService): Boolean {
        if (isRunning && commandServer != null) {
            onLogReceived("sing-box service already running, skipping restart")
            return true
        }

        stopService()
        lastError = null
        synchronized(recentLogs) { recentLogs.clear() }

        return try {
            vpnService = vpn
            lastConfigJson = configJson
            workingDir.mkdirs()
            File(workingDir, "config.json").writeText(configJson)

            // Validate before native service startup so a schema problem is
            // returned as a normal error instead of taking the process down.
            Libbox.checkConfig(configJson)

            val server = Libbox.newCommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            isRunning = true
            isStopping = false

            // CommandStatus is observation only. A transient command socket
            // failure must not tear down a working VPN tunnel.
            runCatching {
                val clientOptions = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    // Go time.Duration is expressed in nanoseconds.
                    statusInterval = 1_000_000_000L
                }
                val client = Libbox.newCommandClient(this, clientOptions)
                client.connect()
                commandClient = client
            }.onFailure { error ->
                commandClient = null
                Log.w(TAG, "Status client unavailable; VPN remains active", error)
                recordLog("实时流量通道暂不可用，但代理隧道已启动：${error.message.orEmpty()}")
            }

            onLogReceived("sing-box v1.14.0 代理隧道已启动")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start sing-box service", e)
            val detail = e.message?.let { "启动失败: $it" }
            recordLog(detail ?: "启动失败: ${e.javaClass.simpleName}")
            stopService()
            failWith(detail)
            false
        }
    }

    fun stopService() {
        if (isStopping) return
        isStopping = true
        isRunning = false

        runCatching { commandClient?.disconnect() }
            .onFailure { Log.w(TAG, "disconnect command client failed", it) }
        commandClient = null

        runCatching { tunPfd?.close() }
            .onFailure { Log.w(TAG, "close TUN fd failed", it) }
        tunPfd = null

        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "close sing-box service failed", it) }
        runCatching { commandServer?.close() }
            .onFailure { Log.w(TAG, "close command server failed", it) }
        commandServer = null

        vpnService = null
        lastConfigJson = null
        isStopping = false
    }

    override fun openTun(options: TunOptions): Int {
        val vpn = vpnService ?: error("VPN service is unavailable")
        if (VpnService.prepare(vpn) != null) {
            error("VPN permission has not been granted")
        }

        val builder = vpn.Builder()
            .setSession("RR Client")
            .setMtu(options.mtu)

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val prefix = inet4Address.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val prefix = inet6Address.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServers = options.dnsServerAddress
                while (dnsServers.hasNext()) {
                    builder.addDnsServer(dnsServers.next())
                }
            }

            val inet4Routes = options.inet4RouteRange
            var hasInet4Route = false
            while (inet4Routes.hasNext()) {
                val prefix = inet4Routes.next()
                builder.addRoute(prefix.address(), prefix.prefix())
                hasInet4Route = true
            }
            if (!hasInet4Route) {
                builder.addRoute("0.0.0.0", 0)
            }

            val inet6Routes = options.inet6RouteRange
            while (inet6Routes.hasNext()) {
                val prefix = inet6Routes.next()
                builder.addRoute(prefix.address(), prefix.prefix())
            }
        }

        val pfd = builder.establish()
            ?: error("VPN interface creation was rejected or revoked")
        tunPfd = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        val protected = vpnService?.protect(fd) == true
        if (!protected) error("Failed to protect outbound socket from VPN loop")
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < 29

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner().apply {
            userId = -1
            userName = ""
            processPath = ""
            setAndroidPackageNames(StringArray(emptyList()))
        }
        val vpn = vpnService ?: return owner
        if (Build.VERSION.SDK_INT < 29) return owner

        return runCatching {
            val connectivity = vpn.getSystemService("connectivity")
                ?: return@runCatching owner
            val method = connectivity.javaClass.getMethod(
                "getConnectionOwnerUid",
                Int::class.javaPrimitiveType,
                InetSocketAddress::class.java,
                InetSocketAddress::class.java
            )
            val uid = method.invoke(
                connectivity,
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            ) as Int
            val packages = vpn.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            ConnectionOwner().apply {
                userId = uid
                userName = packages.firstOrNull().orEmpty()
                processPath = ""
                setAndroidPackageNames(StringArray(packages))
            }
        }.getOrElse {
            Log.d(TAG, "Connection owner lookup unavailable", it)
            owner
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val defaultInterface = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces())
                .firstOrNull { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
        }.getOrNull()

        if (defaultInterface == null) {
            listener.updateDefaultInterface("", -1, false, false)
        } else {
            listener.updateDefaultInterface(defaultInterface.name, defaultInterface.index, false, false)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces()).map { source ->
                BoxNetworkInterface().apply {
                    index = source.index
                    name = source.name.orEmpty()
                    mtu = runCatching { source.mtu }.getOrDefault(1500)
                    addresses = StringArray(
                        source.interfaceAddresses.mapNotNull { interfaceAddress ->
                            val address = interfaceAddress.address ?: return@mapNotNull null
                            val host = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                            "$host/${interfaceAddress.networkPrefixLength.toInt()}"
                        }
                    )
                    flags = 0
                    type = when {
                        name.startsWith("wlan") || name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
                        name.startsWith("rmnet") || name.startsWith("ccmni") -> Libbox.InterfaceTypeCellular
                        name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    dnsServer = StringArray(emptyList())
                    gateway = StringArray(emptyList())
                    metered = false
                }
            }
        }.getOrDefault(emptyList())
        return NetworkInterfaceArray(interfaces)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun sendNotification(notification: Notification) {
        onLogReceived("sing-box notification: ${notification.title}: ${notification.body}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun registerMyInterface(name: String) = Unit

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = Unit

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Platform shell is disabled")

    override fun lookupUser(username: String): PlatformUser =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun lookupSFTPServer(): String =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun tailscaleHostname(): String = "RR Client"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("Platform bridge is disabled")

    override fun serviceStop() {
        stopService()
    }

    override fun serviceReload() {
        val config = lastConfigJson ?: return
        commandServer?.startOrReloadService(config, OverrideOptions())
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() {
        onLogReceived("Native crash request ignored in RR Client")
    }

    override fun writeDebugMessage(message: String) {
        onLogReceived(message)
    }

    override fun connectSSHAgent(): Int = -1

    override fun connected() {
        Log.i(TAG, "CommandClient connected")
    }

    override fun disconnected(message: String) {
        Log.i(TAG, "CommandClient disconnected: $message")
    }

    override fun setDefaultLogLevel(level: Int) = Unit

    override fun clearLogs() = Unit

    override fun writeLogs(messageList: LogIterator) {
        while (messageList.hasNext()) {
            recordLog(messageList.next().message)
        }
    }

    override fun writeStatus(message: StatusMessage) {
        onStatusUpdate(message)
    }

    override fun writeGroups(message: OutboundGroupIterator) = Unit

    override fun writeOutbounds(message: OutboundGroupItemIterator) = Unit

    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit

    override fun updateClashMode(newMode: String) = Unit

    override fun writeConnectionEvents(events: ConnectionEvents) = Unit

    fun isCoreRunning(): Boolean = isRunning

    private class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0

        override fun len(): Int = values.size

        override fun hasNext(): Boolean = index < values.size

        override fun next(): String = values[index++]
    }

    private class NetworkInterfaceArray(
        private val values: List<BoxNetworkInterface>
    ) : NetworkInterfaceIterator {
        private var index = 0

        override fun hasNext(): Boolean = index < values.size

        override fun next(): BoxNetworkInterface = values[index++]
    }

    private companion object {
        const val TAG = "BoxServiceWrapper"
    }
}

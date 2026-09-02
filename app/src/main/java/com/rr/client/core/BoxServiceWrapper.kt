package com.rr.client.core

import android.content.pm.PackageManager.NameNotFoundException
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
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
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/**
 * Thin Android adapter around sing-box libbox.
 *
 * Connectivity is the first milestone: failure of the optional command/status
 * client must not tear down an otherwise working VPN tunnel. All native
 * startup errors are converted into a normal result and exposed to the UI.
 */
class BoxServiceWrapper(
    private val workingDir: File,
    private val onLogReceived: (String) -> Unit,
    private val onStatusUpdate: (StatusMessage) -> Unit,
    private val onServiceStopRequested: () -> Unit
) : PlatformInterface, CommandServerHandler, CommandClientHandler {

    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var vpnService: VpnService? = null
    private var lastConfigJson: String? = null

    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultInterfaceListener: InterfaceUpdateListener? = null
    private var myInterfaceName: String? = null

    @Volatile
    private var running = false

    @Volatile
    private var startError: String? = null

    @Synchronized
    fun startService(configJson: String, vpn: VpnService): Boolean {
        stopService()
        startError = null

        return try {
            vpnService = vpn
            lastConfigJson = configJson

            // Validate before opening a TUN. Removed/unknown 1.14 fields must
            // be shown as a normal connection error, never as an app crash.
            Libbox.checkConfig(configJson)
            workingDir.mkdirs()
            File(workingDir, "last-config.json").writeText(configJson)

            val server = Libbox.newCommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            running = true

            // Traffic/status observation is optional for the first network
            // milestone. A command socket hiccup must not close the tunnel.
            runCatching {
                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    // libbox expects a Go time.Duration in nanoseconds.
                    statusInterval = 1_000_000_000L
                }
                Libbox.newCommandClient(this, options).also { client ->
                    commandClient = client
                    client.connect()
                }
            }.onFailure { error ->
                commandClient = null
                Log.w(TAG, "Status client unavailable; VPN remains active", error)
                onLogReceived("实时流量监听暂不可用，但代理隧道已启动：${readableError(error)}")
            }

            onLogReceived("sing-box v1.14.0 代理隧道已启动")
            true
        } catch (error: Throwable) {
            val message = readableError(error)
            startError = message
            Log.e(TAG, "Failed to start sing-box service", error)
            onLogReceived("启动失败：$message")
            stopService(preserveStartError = true)
            false
        }
    }

    @Synchronized
    fun stopService(preserveStartError: Boolean = false) {
        running = false

        runCatching { commandClient?.disconnect() }
            .onFailure { Log.w(TAG, "disconnect command client failed", it) }
        commandClient = null

        runCatching { commandServer?.closeService() }
            .onFailure { Log.w(TAG, "close sing-box service failed", it) }
        runCatching { commandServer?.close() }
            .onFailure { Log.w(TAG, "close command server failed", it) }
        commandServer = null

        runCatching { tunPfd?.close() }
            .onFailure { Log.w(TAG, "close TUN fd failed", it) }
        tunPfd = null

        unregisterDefaultNetworkCallback()
        vpnService = null
        lastConfigJson = null
        myInterfaceName = null
        if (!preserveStartError) startError = null
    }

    fun lastStartError(): String? = startError

    fun isCoreRunning(): Boolean = running

    // ---------------------------------------------------------------------
    // PlatformInterface
    // ---------------------------------------------------------------------

    override fun openTun(options: TunOptions): Int {
        val vpn = vpnService ?: error("VPN 服务不可用")
        if (VpnService.prepare(vpn) != null) {
            error("VPN 权限尚未授予")
        }

        val builder = vpn.Builder()
            .setSession("RR Client")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                var hasInet4Route = false
                val inet4Routes = options.inet4RouteAddress
                while (inet4Routes.hasNext()) {
                    builder.addRoute(inet4Routes.next().toIpPrefix())
                    hasInet4Route = true
                }
                if (!hasInet4Route && options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                var hasInet6Route = false
                val inet6Routes = options.inet6RouteAddress
                while (inet6Routes.hasNext()) {
                    builder.addRoute(inet6Routes.next().toIpPrefix())
                    hasInet6Route = true
                }
                if (!hasInet6Route && options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4Excluded = options.inet4RouteExcludeAddress
                while (inet4Excluded.hasNext()) {
                    builder.excludeRoute(inet4Excluded.next().toIpPrefix())
                }

                val inet6Excluded = options.inet6RouteExcludeAddress
                while (inet6Excluded.hasNext()) {
                    builder.excludeRoute(inet6Excluded.next().toIpPrefix())
                }
            } else {
                val inet4Routes = options.inet4RouteRange
                while (inet4Routes.hasNext()) {
                    val prefix = inet4Routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }

                val inet6Routes = options.inet6RouteRange
                while (inet6Routes.hasNext()) {
                    val prefix = inet6Routes.next()
                    builder.addRoute(prefix.address(), prefix.prefix())
                }
            }

            val includedPackages = options.includePackage
            while (includedPackages.hasNext()) {
                val packageName = includedPackages.next()
                try {
                    builder.addAllowedApplication(packageName)
                } catch (error: NameNotFoundException) {
                    Log.w(TAG, "Allowed package no longer exists: $packageName", error)
                }
            }

            val excludedPackages = options.excludePackage
            while (excludedPackages.hasNext()) {
                val packageName = excludedPackages.next()
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: NameNotFoundException) {
                    Log.w(TAG, "Excluded package no longer exists: $packageName", error)
                }
            }
        }

        val pfd = builder.establish()
            ?: error("系统拒绝创建 VPN 接口，或 VPN 权限已被撤销")
        tunPfd = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (vpnService?.protect(fd) != true) {
            error("无法保护代理出口套接字，已阻止 VPN 路由环路")
        }
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val vpn = vpnService ?: error("VPN 服务不可用")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("当前 Android 版本不支持按应用识别连接")
        }

        val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager 不可用")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort)
        )
        if (uid == Process.INVALID_UID) error("未找到连接所属应用")

        val packages = vpn.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            processPath = ""
            setAndroidPackageNames(StringArray(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val vpn = vpnService ?: error("VPN 服务不可用")
        val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager 不可用")

        unregisterDefaultNetworkCallback()
        defaultInterfaceListener = listener

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publishDefaultInterface(connectivity)
            override fun onLost(network: Network) = publishDefaultInterface(connectivity)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                publishDefaultInterface(connectivity)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                publishDefaultInterface(connectivity)
        }
        defaultNetworkCallback = callback
        connectivity.registerDefaultNetworkCallback(callback)
        publishDefaultInterface(connectivity)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        unregisterDefaultNetworkCallback()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val vpn = vpnService ?: return NetworkInterfaceArray(emptyList())
        val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkInterfaceArray(emptyList())

        val javaInterfaces = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces()).associateBy { it.name }
        }.getOrDefault(emptyMap())

        val result = linkedMapOf<String, BoxNetworkInterface>()
        connectivity.allNetworks.forEach { network ->
            val linkProperties = connectivity.getLinkProperties(network) ?: return@forEach
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
            val name = linkProperties.interfaceName ?: return@forEach
            val source = javaInterfaces[name] ?: return@forEach

            result[name] = BoxNetworkInterface().apply {
                index = source.index
                this.name = name
                mtu = runCatching { source.mtu }.getOrDefault(1500)
                addresses = StringArray(
                    source.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val host = interfaceAddress.address?.hostAddress
                            ?.substringBefore('%')
                            ?: return@mapNotNull null
                        "$host/${interfaceAddress.networkPrefixLength.toInt()}"
                    }
                )
                flags = buildInterfaceFlags(source)
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = StringArray(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }
                )
                gateway = StringArray(
                    linkProperties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway?.hostAddress?.substringBefore('%') }
                )
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }

        // Some vendor ROMs temporarily omit the physical network from
        // ConnectivityManager during VPN transitions. Keep a conservative
        // Java-NetworkInterface fallback so libbox still sees the uplink.
        if (result.isEmpty()) {
            javaInterfaces.values
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .filterNot { it.name == myInterfaceName || it.name.startsWith("tun") }
                .forEach { source ->
                    result[source.name] = BoxNetworkInterface().apply {
                        index = source.index
                        name = source.name.orEmpty()
                        mtu = runCatching { source.mtu }.getOrDefault(1500)
                        addresses = StringArray(
                            source.interfaceAddresses.mapNotNull { interfaceAddress ->
                                val host = interfaceAddress.address?.hostAddress
                                    ?.substringBefore('%')
                                    ?: return@mapNotNull null
                                "$host/${interfaceAddress.networkPrefixLength.toInt()}"
                            }
                        )
                        flags = buildInterfaceFlags(source)
                        type = interfaceTypeFromName(name)
                        dnsServer = StringArray(emptyList())
                        gateway = StringArray(emptyList())
                        metered = false
                    }
                }
        }

        return NetworkInterfaceArray(result.values.toList())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun sendNotification(notification: Notification) {
        onLogReceived("sing-box：${notification.title} ${notification.body}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun registerMyInterface(name: String) {
        myInterfaceName = name
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() = Unit

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("平台 Shell 已关闭")

    override fun lookupUser(username: String): PlatformUser =
        throw UnsupportedOperationException("平台 Shell 已关闭")

    override fun lookupSFTPServer(): String =
        throw UnsupportedOperationException("平台 Shell 已关闭")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("平台 Shell 已关闭")

    override fun tailscaleHostname(): String = "RR Client"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("平台 Bridge 已关闭")

    // ---------------------------------------------------------------------
    // CommandServerHandler
    // ---------------------------------------------------------------------

    override fun serviceStop() {
        // Never close CommandServer recursively from its own callback.
        onServiceStopRequested()
    }

    override fun serviceReload() {
        val config = lastConfigJson ?: return
        runCatching { commandServer?.startOrReloadService(config, OverrideOptions()) }
            .onFailure { error ->
                startError = readableError(error)
                onLogReceived("重载失败：${readableError(error)}")
            }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(enabled: Boolean) = Unit

    override fun triggerNativeCrash() {
        onLogReceived("已忽略内核崩溃测试请求")
    }

    override fun writeDebugMessage(message: String) {
        onLogReceived(message)
    }

    override fun connectSSHAgent(): Int = -1

    // ---------------------------------------------------------------------
    // CommandClientHandler
    // ---------------------------------------------------------------------

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
            onLogReceived(messageList.next().message)
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

    private fun publishDefaultInterface(connectivity: ConnectivityManager) {
        val listener = defaultInterfaceListener ?: return
        val network = choosePhysicalNetwork(connectivity)
        if (network == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }

        val linkProperties = connectivity.getLinkProperties(network)
        val capabilities = connectivity.getNetworkCapabilities(network)
        val interfaceName = linkProperties?.interfaceName
        val interfaceIndex = interfaceName?.let {
            runCatching { JavaNetworkInterface.getByName(it)?.index ?: -1 }.getOrDefault(-1)
        } ?: -1

        if (interfaceName.isNullOrBlank() || interfaceIndex < 0) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }

        val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        listener.updateDefaultInterface(interfaceName, interfaceIndex, metered, false)
    }

    private fun choosePhysicalNetwork(connectivity: ConnectivityManager): Network? {
        fun isPhysical(network: Network): Boolean {
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            val linkProperties = connectivity.getLinkProperties(network) ?: return false
            val name = linkProperties.interfaceName ?: return false
            return !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                name != myInterfaceName &&
                !name.startsWith("tun") &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        connectivity.activeNetwork?.takeIf(::isPhysical)?.let { return it }

        return connectivity.allNetworks
            .filter(::isPhysical)
            .sortedByDescending { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                when {
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true -> 3
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 2
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1
                    else -> 0
                }
            }
            .firstOrNull()
    }

    private fun unregisterDefaultNetworkCallback() {
        val callback = defaultNetworkCallback ?: return
        val vpn = vpnService
        if (vpn != null) {
            runCatching {
                vpn.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
            }.onFailure { Log.d(TAG, "Default network callback already unregistered", it) }
        }
        defaultNetworkCallback = null
        defaultInterfaceListener = null
    }

    private fun buildInterfaceFlags(source: JavaNetworkInterface): Int {
        var flags = 0
        if (runCatching { source.isUp }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (runCatching { source.isLoopback }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_LOOPBACK
        }
        if (runCatching { source.isPointToPoint }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_POINTOPOINT
        }
        if (runCatching { source.supportsMulticast() }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_MULTICAST
        }
        return flags
    }

    private fun interfaceTypeFromName(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
        name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    private fun RoutePrefix.toIpPrefix(): IpPrefix = IpPrefix("${address()}/${prefix()}")

    private fun readableError(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(3)
            .toList()
        return chain.joinToString("；").ifBlank { error.javaClass.simpleName }
    }

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

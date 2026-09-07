package com.rr.client.core

import com.rr.client.vpn.NetworkContinuityMonitor
import com.rr.client.lab.ConnectionLogDeduplicator
import com.rr.client.lab.ConnectionRouteLog
import com.rr.client.lab.ConnectionRouteRecord
import com.rr.client.lab.ConnectionRouteMessage
import com.rr.client.lab.RRLogStore
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
import io.nekohasekai.libbox.ConnectionEvent
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
    @Volatile private var isRunning = false
    @Volatile private var isStopping = false

    private var interfaceMonitor: NetworkContinuityMonitor? = null

    private val recentLogs = ArrayDeque<String>()
    private val connectionLogIds = ConnectionLogDeduplicator()
    private val appLabels = LinkedHashMap<String, String>()
    @Volatile private var collectConnectionLogs = false
    @Volatile private var commandGeneration = 0L

    @Volatile
    var lastError: String? = null
        private set

    private fun recordLog(rawLine: String) {
        val line = com.rr.client.security.SecretRedactor.redact(rawLine)
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
            recordLog("sing-box service already running, skipping restart")
            return true
        }

        stopService()
        lastError = null
        synchronized(recentLogs) { recentLogs.clear() }

        return try {
            vpnService = vpn
            lastConfigJson = configJson
            workingDir.mkdirs()
            val configFile = File(workingDir, "config.json")
            configFile.writeText(configJson)
            recordLog("运行配置路径：${configFile.absolutePath}")

            Libbox.checkConfig(configJson)

            val server = Libbox.newCommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            isRunning = true
            isStopping = false
            collectConnectionLogs = ConnectionRouteLog.enabledForConfig(configJson)
            RRLogStore.setConnectionLoggingActive(false)

            runCatching {
                val clientOptions = CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    // Connections are optional diagnostics; lightweight mode keeps status only.
                    if (collectConnectionLogs) addCommand(Libbox.CommandConnections)
                    statusInterval = 1_000_000_000L
                }
                val generation = commandGeneration
                val handler = object : CommandClientHandler by this@BoxServiceWrapper {
                    override fun writeConnectionEvents(events: ConnectionEvents) {
                        if (generation == commandGeneration) this@BoxServiceWrapper.writeConnectionEvents(events)
                    }

                    override fun disconnected(message: String) {
                        if (generation == commandGeneration) this@BoxServiceWrapper.disconnected(message)
                    }
                }
                val client = Libbox.newCommandClient(handler, clientOptions)
                client.connect()
                commandClient = client
            }.onFailure { error ->
                commandClient = null
                collectConnectionLogs = false
                RRLogStore.setConnectionLoggingActive(false)
                Log.w(TAG, "Status client unavailable; VPN remains active", error)
                recordLog("实时流量通道暂不可用，但代理隧道已启动：${error.message.orEmpty()}")
            }

            recordLog("sing-box v1.14.0 代理隧道已启动")
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
        commandGeneration++
        collectConnectionLogs = false
        RRLogStore.setConnectionLoggingActive(false)
        connectionLogIds.clear()
        synchronized(appLabels) { appLabels.clear() }
        interfaceMonitor?.stop()
        interfaceMonitor = null

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
            .setSession("RRBOX")
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

            val includePackage = options.includePackage
            while (includePackage.hasNext()) {
                val packageName = includePackage.next()
                runCatching { builder.addAllowedApplication(packageName) }
                    .onSuccess { recordLog("仅选中代理：$packageName") }
                    .onFailure {
                        Log.w(TAG, "Unable to include package $packageName", it)
                        throw it
                    }
            }

            val excludePackage = options.excludePackage
            while (excludePackage.hasNext()) {
                val packageName = excludePackage.next()
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onSuccess { recordLog("绕过 VPN：$packageName") }
                    .onFailure {
                        Log.w(TAG, "Unable to exclude package $packageName", it)
                        throw it
                    }
            }
        }

        val pfd = builder.establish()
            ?: error("VPN interface creation was rejected or revoked")
        tunPfd = pfd
        recordLog("Android TUN 已建立，fd=${pfd.fd}")
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
            val connectivity = vpn.getSystemService(ConnectivityManager::class.java)
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
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
        interfaceMonitor?.stop()
        val vpn = vpnService
        if (vpn == null) {
            listener.updateDefaultInterface("", -1, false, false)
            return
        }
        // libbox needs ongoing interface updates, not just a startup snapshot.
        // Reuse the same physical-network policy as the continuity guard.
        interfaceMonitor = NetworkContinuityMonitor(vpn) { path ->
            runCatching {
                val networkInterface = path?.interfaceName?.takeUnless { it == "--" }
                    ?.let { runCatching { JavaNetworkInterface.getByName(it) }.getOrNull() }
                if (path == null || networkInterface == null) {
                    listener.updateDefaultInterface("", -1, false, false)
                } else {
                    val capabilities = vpn.getSystemService(ConnectivityManager::class.java)
                        .getNetworkCapabilities(path.network)
                    val metered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                    listener.updateDefaultInterface(networkInterface.name, networkInterface.index, metered, false)
                    recordLog("默认物理出口：${networkInterface.name} (${networkInterface.index})")
                }
            }.onFailure { Log.w(TAG, "Default interface notification failed", it) }
        }.also { it.start() }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceMonitor?.stop()
        interfaceMonitor = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val vpn = vpnService
        val connectivity = vpn?.getSystemService(ConnectivityManager::class.java)
        val androidNetworks = connectivity?.allNetworks.orEmpty().mapNotNull { network ->
            val properties = connectivity?.getLinkProperties(network) ?: return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null
            val capabilities = connectivity.getNetworkCapabilities(network)
            name to Pair(properties, capabilities)
        }.toMap()

        val interfaces = runCatching {
            Collections.list(JavaNetworkInterface.getNetworkInterfaces()).map { source ->
                val androidNetwork = androidNetworks[source.name]
                val properties = androidNetwork?.first
                val capabilities = androidNetwork?.second

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
                    flags = buildInterfaceFlags(source, capabilities)
                    type = when {
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Libbox.InterfaceTypeWIFI
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Libbox.InterfaceTypeCellular
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Libbox.InterfaceTypeEthernet
                        source.name.startsWith("wlan") || source.name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
                        source.name.startsWith("rmnet") || source.name.startsWith("ccmni") -> Libbox.InterfaceTypeCellular
                        source.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    dnsServer = StringArray(
                        properties?.dnsServers
                            ?.mapNotNull { it.hostAddress }
                            .orEmpty()
                    )
                    gateway = StringArray(
                        properties?.routes
                            ?.filter { it.destination.prefixLength == 0 }
                            ?.mapNotNull { it.gateway?.hostAddress }
                            .orEmpty()
                    )
                    metered = capabilities?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                    ) == false
                }
            }
        }.getOrDefault(emptyList())

        val usable = interfaces.filter { it.flags and OsConstants.IFF_UP != 0 }
        recordLog("libbox 可用网卡：${usable.joinToString { "${it.name}(${it.index})" }.ifBlank { "无" }}")
        return NetworkInterfaceArray(interfaces)
    }

    private fun buildInterfaceFlags(
        source: JavaNetworkInterface,
        capabilities: NetworkCapabilities?
    ): Int {
        var flags = 0
        val isUp = runCatching { source.isUp }.getOrDefault(false) ||
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (isUp) {
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

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun sendNotification(notification: Notification) {
        recordLog("sing-box notification: ${notification.title}: ${notification.body}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) = Unit

    override fun registerMyInterface(name: String) {
        recordLog("libbox 注册自身网卡：$name")
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
    ): ShellSession = throw UnsupportedOperationException("Platform shell is disabled")

    override fun lookupUser(username: String): PlatformUser =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun lookupSFTPServer(): String =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("Platform shell is disabled")

    override fun tailscaleHostname(): String = "RRBOX"

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
        recordLog("Native crash request ignored in RRBOX")
    }

    override fun writeDebugMessage(message: String) {
        recordLog(message)
    }

    override fun connectSSHAgent(): Int = -1

    override fun connected() {
        recordLog("CommandClient connected")
    }

    override fun disconnected(message: String) {
        if (collectConnectionLogs) RRLogStore.setConnectionLoggingActive(false)
        recordLog("CommandClient disconnected: $message")
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

    override fun writeConnectionEvents(events: ConnectionEvents) {
        if (!collectConnectionLogs || !isRunning) return
        RRLogStore.setConnectionLoggingActive(true)
        runCatching { collectConnectionEvents(events) }.onFailure { error ->
            // Optional diagnostics must never escape across gomobile or stop the data plane.
            RRLogStore.record("LOG", "连接流向采集失败（隧道继续运行）：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun collectConnectionEvents(events: ConnectionEvents) {
        val iterator = events.iterator()
        val batch = ArrayList<ConnectionRouteMessage>(100)
        var failed = 0
        while (collectConnectionLogs && isRunning && iterator.hasNext()) {
            val event = iterator.next()
            runCatching { formatConnectionEvent(event) }
                .onFailure { failed++ }.getOrNull()?.let(batch::add)
            if (batch.size >= 100) {
                RRLogStore.recordConnections(batch)
                batch.clear()
            }
        }
        RRLogStore.recordConnections(batch)
        if (failed > 0) RRLogStore.record("LOG", "本批 $failed 条连接元数据暂不可用，已跳过；隧道继续运行。")
    }

    private fun formatConnectionEvent(event: ConnectionEvent): ConnectionRouteMessage? {
        // Traffic deltas/close events have no new routing decision. Never log per packet.
        if (event.type != Libbox.ConnectionEventNew.toInt() &&
            event.type != Libbox.ConnectionEventClosed.toInt()) return null
        val connection = event.connection ?: return null
        if (!connectionLogIds.accept(event.getID())) return null
        val hev = connection.inbound == HevConfigAdapter.SOCKS_TAG
        val process = connection.processInfo.takeUnless { hev }
        val packages = ArrayList<String>()
        process?.packageNames()?.let { names ->
            while (names.hasNext() && packages.size < 16) packages.add(names.next().take(256))
        }
        val application = packages.takeIf { it.size == 1 }?.first()?.let(::applicationLabel)
        val message = ConnectionRouteLog.format(ConnectionRouteRecord(
            application = application,
            packages = packages,
            uid = process?.getUserID(),
            domain = connection.domain.orEmpty(),
            destination = connection.destination.orEmpty(),
            network = connection.network.orEmpty(),
            outbound = connection.outbound.orEmpty(),
            outboundType = connection.outboundType.orEmpty(),
            rule = connection.rule.orEmpty(),
            hev = hev
        ))
        return ConnectionRouteMessage(
            timestamp = connection.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            message = message + if (connection.closedAt > 0) "\n状态：已结束（历史或补录）" else ""
        )
    }

    private fun applicationLabel(packageName: String): String = synchronized(appLabels) {
        appLabels[packageName] ?: run {
            val label = runCatching {
                val manager = vpnService?.packageManager ?: return@runCatching packageName
                manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString().take(120)
            }.getOrDefault(packageName)
            if (appLabels.size >= 256) appLabels.entries.iterator().run { next(); remove() }
            appLabels[packageName] = label
            label
        }
    }

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

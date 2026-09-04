package com.rr.client.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.rr.client.core.NodeLatencyState
import com.rr.client.core.NodeLatencyTester
import com.rr.client.core.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkDiagnostics {
    suspend fun collect(
        context: Context,
        node: ProxyNode?,
        engine: String,
        vpnRunning: Boolean
    ): DiagnosticReport = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = cm?.activeNetwork
        val activeCaps = activeNetwork?.let { network -> cm.getNetworkCapabilities(network) }
        val activeLink = activeNetwork?.let { network -> cm.getLinkProperties(network) }

        val physicalNetwork = cm?.let { manager ->
            manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }
        val physicalCaps = physicalNetwork?.let { network -> cm?.getNetworkCapabilities(network) }
        val physicalLink = physicalNetwork?.let { network -> cm?.getLinkProperties(network) }

        val tun = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { nic ->
                    runCatching { nic.isUp }.getOrDefault(false) &&
                        (nic.name.startsWith("tun") || nic.name.startsWith("ppp"))
                }
        }.getOrNull()

        val linkForAddresses = physicalLink ?: activeLink
        val snapshot = NetworkSnapshot(
            transport = transportLabel(physicalCaps ?: activeCaps),
            activeInterface = physicalLink?.interfaceName ?: activeLink?.interfaceName ?: "--",
            mtu = (physicalLink?.mtu ?: activeLink?.mtu ?: 0).coerceAtLeast(0),
            ipv4Addresses = linkForAddresses.addressesV4(),
            ipv6Addresses = linkForAddresses.addressesV6(),
            dnsServers = linkForAddresses?.dnsServers?.mapNotNull { it.hostAddress }?.distinct().orEmpty(),
            privateDns = privateDnsLabel(linkForAddresses),
            validated = (physicalCaps ?: activeCaps)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = cm?.isActiveNetworkMetered == true,
            vpnInterface = tun?.name,
            vpnMtu = tun?.let { runCatching { it.mtu }.getOrNull() }
        )

        val checks = mutableListOf<LabCheck>()
        checks += LabCheck(
            "活动网络",
            if (activeNetwork != null) LabCheckStatus.PASS else LabCheckStatus.FAIL,
            if (activeNetwork != null) "${snapshot.transport} / ${snapshot.activeInterface}" else "Android 未报告活动网络"
        )
        checks += LabCheck(
            "Internet 验证",
            if (snapshot.validated) LabCheckStatus.PASS else LabCheckStatus.WARN,
            if (snapshot.validated) "Android 标记为 VALIDATED" else "当前网络未被 Android 标记为 VALIDATED"
        )
        checks += LabCheck(
            "IPv4",
            if (snapshot.ipv4Addresses.isNotEmpty()) LabCheckStatus.PASS else LabCheckStatus.WARN,
            snapshot.ipv4Addresses.joinToString().ifBlank { "未发现 IPv4 地址" }
        )
        checks += LabCheck(
            "IPv6",
            if (snapshot.ipv6Addresses.isNotEmpty()) LabCheckStatus.PASS else LabCheckStatus.INFO,
            snapshot.ipv6Addresses.joinToString().ifBlank { "当前物理网络未发现 IPv6 地址" }
        )
        checks += LabCheck(
            "DNS",
            if (snapshot.dnsServers.isNotEmpty()) LabCheckStatus.PASS else LabCheckStatus.WARN,
            snapshot.dnsServers.joinToString().ifBlank { "Android 未报告 DNS 服务器" }
        )
        checks += LabCheck("当前转发引擎", LabCheckStatus.INFO, engine)

        if (vpnRunning) {
            checks += LabCheck(
                "VPN TUN",
                if (tun != null) LabCheckStatus.PASS else LabCheckStatus.FAIL,
                tun?.let { "${it.name} / MTU ${runCatching { it.mtu }.getOrDefault(0)}" }
                    ?: "VPN 显示运行，但没有发现活动 TUN 接口"
            )
            checks += LabCheck(
                "节点主动探测",
                LabCheckStatus.INFO,
                "VPN 运行期间不执行额外直连 socket 探测，避免诊断工具干扰稳定数据面"
            )
        } else {
            checks += LabCheck(
                "VPN TUN",
                LabCheckStatus.INFO,
                tun?.let { "发现 ${it.name}，但 RRBOX 当前未标记运行" } ?: "RRBOX 当前未连接"
            )
        }

        if (node == null) {
            checks += LabCheck("当前节点", LabCheckStatus.WARN, "未选择节点")
        } else {
            checks += LabCheck(
                "当前节点",
                LabCheckStatus.PASS,
                "${node.tag} / ${maskHost(node.server)}:${node.serverPort}"
            )
            if (!vpnRunning) {
                val resolved = runCatching {
                    InetAddress.getAllByName(node.server)
                        .mapNotNull { it.hostAddress }
                        .distinct()
                }.getOrElse { emptyList() }
                checks += LabCheck(
                    "节点 DNS 解析",
                    if (resolved.isNotEmpty()) LabCheckStatus.PASS else LabCheckStatus.FAIL,
                    resolved.joinToString().ifBlank { "解析失败" }
                )

                val ping = NodeLatencyTester.ping(node.server)
                checks += when (ping) {
                    is NodeLatencyState.Success -> LabCheck("节点 ICMP", LabCheckStatus.PASS, "${ping.millis} ms")
                    NodeLatencyState.Timeout -> LabCheck("节点 ICMP", LabCheckStatus.WARN, "超时；不代表代理协议一定不可用")
                    else -> LabCheck("节点 ICMP", LabCheckStatus.INFO, "未完成")
                }
            }
        }

        val selfCheck = StartupSelfCheck.report.value
        if (selfCheck != null) {
            val failed = selfCheck.checks.count { it.status == LabCheckStatus.FAIL }
            checks += LabCheck(
                "启动自检",
                if (failed == 0) LabCheckStatus.PASS else LabCheckStatus.FAIL,
                if (failed == 0) "核心文件、规则和数据库检查通过" else "$failed 项失败"
            )
        }

        DiagnosticReport(snapshot = snapshot, checks = checks).also { report ->
            RRLogStore.record(
                "DIAG",
                "诊断完成: ${report.checks.count { it.status == LabCheckStatus.PASS }} PASS, " +
                    "${report.checks.count { it.status == LabCheckStatus.FAIL }} FAIL"
            )
        }
    }

    private fun LinkProperties?.addressesV4(): List<String> = this?.linkAddresses
        ?.mapNotNull { link -> link.address.hostAddress?.takeIf { !it.contains(':') } }
        ?.distinct()
        .orEmpty()

    private fun LinkProperties?.addressesV6(): List<String> = this?.linkAddresses
        ?.mapNotNull { link -> link.address.hostAddress?.substringBefore('%')?.takeIf { it.contains(':') } }
        ?.distinct()
        .orEmpty()

    private fun privateDnsLabel(link: LinkProperties?): String? {
        if (link == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return if (link.isPrivateDnsActive) link.privateDnsServerName ?: "已启用（自动）" else null
    }

    private fun transportLabel(caps: NetworkCapabilities?): String = when {
        caps == null -> "未知"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "其他网络"
    }

    private fun maskHost(value: String): String {
        val host = value.trim()
        val ipv4 = host.split('.')
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }) {
            return "${ipv4.first()}.***.***.${ipv4.last()}"
        }
        if (host.contains(':')) return "[${host.substringBefore(':')}:****]"
        if (host.length <= 4) return "***"
        return "${host.take(2)}***${host.takeLast(2)}"
    }
}

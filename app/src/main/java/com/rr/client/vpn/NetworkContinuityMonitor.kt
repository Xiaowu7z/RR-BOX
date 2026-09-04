package com.rr.client.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Event-driven physical-network observer used by RRBOX while the VPN service is alive.
 *
 * It does not send heartbeat packets. Android connectivity callbacks are enough to tell us when
 * Wi-Fi/cellular/interface/IP state changes. The service performs one cheap data-plane health check
 * after a preferred-path change and only rebuilds the VPN when the local core/native data plane has
 * actually stopped.
 */
class NetworkContinuityMonitor(
    context: Context,
    private val onPreferredPathChanged: (PhysicalPath) -> Unit
) {
    data class PhysicalPath(
        val network: Network,
        val transport: String,
        val interfaceName: String,
        val validated: Boolean,
        val signature: String
    )

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val candidates = linkedMapOf<Network, Candidate>()
    private val lock = Any()
    private var registered = false
    private var lastSignature: String? = null

    private data class Candidate(
        val network: Network,
        val capabilities: NetworkCapabilities,
        val linkProperties: LinkProperties?
    )

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val link = connectivity.getLinkProperties(network)
            synchronized(lock) {
                if (isPhysicalInternet(capabilities)) {
                    candidates[network] = Candidate(network, capabilities, link)
                } else {
                    candidates.remove(network)
                }
            }
            publishPreferredIfChanged()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val caps = connectivity.getNetworkCapabilities(network)
            synchronized(lock) {
                if (caps != null && isPhysicalInternet(caps)) {
                    candidates[network] = Candidate(network, caps, linkProperties)
                } else {
                    candidates.remove(network)
                }
            }
            publishPreferredIfChanged()
        }

        override fun onLost(network: Network) {
            synchronized(lock) { candidates.remove(network) }
            publishPreferredIfChanged()
        }
    }

    fun start() {
        if (registered) return
        val manager = connectivity
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, callback)
            registered = true
            manager.allNetworks.forEach(::refresh)
        }.onFailure { Log.w(TAG, "Unable to register physical network monitor", it) }
    }

    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        synchronized(lock) { candidates.clear() }
        lastSignature = null
    }

    private fun refresh(network: Network) {
        val caps = connectivity.getNetworkCapabilities(network)
        val link = connectivity.getLinkProperties(network)
        synchronized(lock) {
            if (caps != null && isPhysicalInternet(caps)) {
                candidates[network] = Candidate(network, caps, link)
            } else {
                candidates.remove(network)
            }
        }
        publishPreferredIfChanged()
    }

    private fun publishPreferredIfChanged() {
        val best = synchronized(lock) {
            candidates.values.maxWithOrNull(
                compareBy<Candidate> { score(it.capabilities, it.linkProperties) }
                    .thenBy { it.linkProperties?.interfaceName.orEmpty() }
            )
        } ?: return

        val link = best.linkProperties
        val caps = best.capabilities
        val transport = transportLabel(caps)
        val interfaceName = link?.interfaceName.orEmpty().ifBlank { "--" }
        val addresses = link?.linkAddresses
            ?.mapNotNull { it.address.hostAddress?.substringBefore('%') }
            ?.sorted()
            .orEmpty()
        val dns = link?.dnsServers?.mapNotNull { it.hostAddress }?.sorted().orEmpty()
        val signature = listOf(transport, interfaceName, addresses.joinToString(","), dns.joinToString(","))
            .joinToString("|")
        if (signature == lastSignature) return
        lastSignature = signature

        onPreferredPathChanged(
            PhysicalPath(
                network = best.network,
                transport = transport,
                interfaceName = interfaceName,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                signature = signature
            )
        )
    }

    private fun isPhysicalInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

    /** Same policy as Network Lab diagnostics: a validated Wi-Fi beats a validated cellular path. */
    private fun score(caps: NetworkCapabilities, link: LinkProperties?): Int {
        var score = 0
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 20_000
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) score += 4_000
        score += when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3_000
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2_000
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1_000
            else -> 200
        }
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)) score += 100
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) score += 50
        if (!link?.interfaceName.isNullOrBlank()) score += 20
        if (!link?.linkAddresses.isNullOrEmpty()) score += 10
        return score
    }

    private fun transportLabel(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        else -> "其他网络"
    }

    private companion object {
        const val TAG = "NetworkContinuity"
    }
}

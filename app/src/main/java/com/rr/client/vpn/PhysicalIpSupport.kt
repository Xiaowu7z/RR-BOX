package com.rr.client.vpn

import android.content.Context
import android.net.LinkProperties
import android.net.RouteInfo
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** A local route snapshot, not an Internet reachability probe. Unknown snapshots never enable recovery. */
object PhysicalIpSupport {
    data class Support(val known: Boolean = false, val hasIPv4: Boolean = false, val hasUsableIPv6: Boolean = false)
    data class DefaultRoute(val address: InetAddress, val prefixLength: Int,
        val interfaceName: String?, val unicast: Boolean)

    fun forPreferredNetwork(context: Context): Support = runCatching {
        fromLinkProperties(NetworkContinuityMonitor.preferredPhysicalLink(context))
    }.getOrDefault(Support())

    private fun fromLinkProperties(link: LinkProperties?): Support {
        // Route types became a public API in 33. Older Android versions keep their
        // original routing instead of inferring that an unknown route is usable.
        if (link == null || Build.VERSION.SDK_INT < 33) return Support()
        return evaluate(link.interfaceName, link.linkAddresses.map { it.address }, link.routes.map {
            DefaultRoute(it.destination.address, it.destination.prefixLength, it.`interface`,
                it.type == RouteInfo.RTN_UNICAST)
        })
    }

    /** Stacked CLAT links are deliberately not inferred as a native IPv4 route on this interface. */
    internal fun evaluate(interfaceName: String?, addresses: List<InetAddress>, routes: List<DefaultRoute>): Support {
        if (interfaceName.isNullOrBlank()) return Support()
        val defaults = routes.filter { it.unicast && it.prefixLength == 0 && it.interfaceName == interfaceName }
        val ipv4 = addresses.any { it is Inet4Address && !it.isAnyLocalAddress &&
            !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isMulticastAddress } &&
            defaults.any { it.address is Inet4Address }
        val ipv6 = addresses.any(::isGlobalIpv6) && defaults.any { it.address is Inet6Address }
        return Support(known = true, hasIPv4 = ipv4, hasUsableIPv6 = ipv6)
    }

    /** Public global unicast space; link-local, ULA, loopback and multicast do not qualify. */
    internal fun isGlobalIpv6(address: InetAddress): Boolean = address is Inet6Address &&
        (address.address[0].toInt() and 0xe0) == 0x20
}

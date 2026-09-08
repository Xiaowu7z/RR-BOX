package com.rr.client.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalIpSupportTest {
    private fun address(text: String) = InetAddress.getByName(text)
    private fun route(text: String, iface: String = "wlan0", prefix: Int = 0, unicast: Boolean = true) =
        PhysicalIpSupport.DefaultRoute(address(text), prefix, iface, unicast)
    private fun support(vararg addresses: String, routes: List<PhysicalIpSupport.DefaultRoute>) =
        PhysicalIpSupport.evaluate("wlan0", addresses.map(::address), routes)

    @Test
    fun ipv4OnlyWifiHasEvidenceForRecoveryWithoutTreatingLinkLocalIpv6AsUsable() {
        assertEquals(PhysicalIpSupport.Support(true, true, false),
            support("192.168.1.2", "fe80::1234", routes = listOf(route("0.0.0.0"))))
    }

    @Test
    fun dualStackAndIpv6OnlyRetainTheirOriginalIpv6Destinations() {
        assertEquals(PhysicalIpSupport.Support(true, true, true),
            support("192.168.1.2", "2409:8c54::1234", routes = listOf(route("0.0.0.0"), route("::"))))
        assertEquals(PhysicalIpSupport.Support(true, false, true),
            support("2409:8c54::1234", routes = listOf(route("::"))))
    }

    @Test
    fun addressWithoutItsDefaultRouteAndDefaultRouteWithoutAddressDoNotProveSupport() {
        assertFalse(support("192.168.1.2", routes = emptyList()).hasIPv4)
        assertFalse(support("fe80::1", routes = listOf(route("0.0.0.0"))).hasIPv4)
        assertFalse(support("2409:8c54::1234", routes = emptyList()).hasUsableIPv6)
        assertFalse(support("fe80::1", "fd12::1", routes = listOf(route("::"))).hasUsableIPv6)
    }

    @Test
    fun rejectRoutesOtherInterfacesAndStackedClatAreNotCountedAsNativeDefaults() {
        assertFalse(support("192.168.1.2", routes = listOf(route("0.0.0.0", unicast = false))).hasIPv4)
        assertFalse(support("192.168.1.2", routes = listOf(route("0.0.0.0", iface = "v4-wlan0"))).hasIPv4)
        assertFalse(support("2409:8c54::1", routes = listOf(route("::", iface = "rmnet_data0"))).hasUsableIPv6)
        assertFalse(support("2409:8c54::1", routes = listOf(route("::", prefix = 64))).hasUsableIPv6)
    }

    @Test
    fun unknownSnapshotAndSpecialAddressesDoNotEnableCompatibility() {
        assertEquals(PhysicalIpSupport.Support(), PhysicalIpSupport.evaluate(null, emptyList(), emptyList()))
        for (text in listOf("::", "::1", "fe80::1", "fd12::1", "ff02::1", "fec0::1")) {
            assertFalse(text, PhysicalIpSupport.isGlobalIpv6(address(text)))
        }
        assertTrue(PhysicalIpSupport.isGlobalIpv6(address("2409:8c54::1")))
        assertFalse(support("127.0.0.1", "169.254.1.1", "224.0.0.1", "0.0.0.0",
            routes = listOf(route("0.0.0.0"))).hasIPv4)
    }
}

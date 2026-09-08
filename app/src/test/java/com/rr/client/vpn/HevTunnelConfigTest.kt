package com.rr.client.vpn

import com.rr.client.core.HevConfigAdapter
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class HevTunnelConfigTest {
    @Test
    fun productionHighPerformanceProfileContainsValidatedKnobs() {
        val yaml = HevTunnelConfig.build(HevConfigAdapter.SOCKS_PORT)

        assertTrue(yaml.contains("mtu: 8500"))
        assertTrue(yaml.contains("ipv4: 198.18.0.1"))
        assertFalse(yaml.contains("mapdns:"))
        assertFalse(yaml.contains("100.64."))
        assertTrue(yaml.contains("port: ${HevConfigAdapter.SOCKS_PORT}"))
        assertTrue(yaml.contains("pipeline: true"))
        assertTrue(yaml.contains("tcp-fastopen: true"))
        assertTrue(yaml.contains("tcp-buffer-size: 131072"))
        assertTrue(yaml.contains("udp-recv-buffer-size: 1048576"))
        assertTrue(yaml.contains("udp-copy-buffer-nums: 32"))
        assertTrue(yaml.contains("log-level: error"))
    }
}

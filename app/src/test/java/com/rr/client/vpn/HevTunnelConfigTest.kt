package com.rr.client.vpn

import com.rr.client.core.HevConfigAdapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevTunnelConfigTest {
    @Test
    fun highThroughputProfileUsesMappedDnsAndLargeVirtualMtu() {
        val yaml = HevTunnelConfig.build(HevConfigAdapter.SOCKS_PORT)

        assertTrue(yaml.contains("mtu: 8500"))
        assertTrue(yaml.contains("ipv4: 198.18.0.1"))
        assertTrue(yaml.contains("address: 198.18.0.2"))
        assertTrue(yaml.contains("network: 100.64.0.0"))
        assertTrue(yaml.contains("port: ${HevConfigAdapter.SOCKS_PORT}"))
        assertTrue(yaml.contains("tcp-buffer-size: 131072"))
        assertTrue(yaml.contains("udp-recv-buffer-size: 1048576"))
        assertTrue(yaml.contains("udp-copy-buffer-nums: 32"))
        assertTrue(yaml.contains("log-level: error"))
        assertFalse(yaml.contains("pipeline: true"))
        assertFalse(yaml.contains("tcp-fastopen: true"))
    }

    @Test
    fun latencyCandidateOnlyAddsHandshakeKnobs() {
        val yaml = HevTunnelConfig.build(
            HevConfigAdapter.SOCKS_PORT,
            latencyCandidate = true
        )

        assertTrue(yaml.contains("pipeline: true"))
        assertTrue(yaml.contains("tcp-fastopen: true"))
        assertTrue(yaml.contains("mtu: 8500"))
        assertTrue(yaml.contains("tcp-buffer-size: 131072"))
        assertTrue(yaml.contains("udp-recv-buffer-size: 1048576"))
        assertTrue(yaml.contains("udp-copy-buffer-nums: 32"))
    }
}

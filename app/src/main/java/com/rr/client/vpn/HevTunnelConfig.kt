package com.rr.client.vpn

/**
 * HEV native data-plane profile.
 *
 * Real-device A/B testing through v2.8 established the production HEV profile used by RRBOX:
 * 8500-byte virtual TUN MTU, mapped DNS, enlarged native buffers, SOCKS5 handshake pipelining and
 * best-effort client TCP Fast Open. The pipeline/TFO pair reduced HEV cold TLS/TTFB variance while
 * preserving its throughput and CPU advantage in the validated A/B path.
 */
object HevTunnelConfig {
    const val MTU = 8500
    const val IPV4_CLIENT = "198.18.0.1"
    const val IPV4_PREFIX = 30
    const val MAPPED_DNS = "198.18.0.2"
    const val SOCKS_HOST = "127.0.0.1"

    fun build(socksPort: Int): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $IPV4_CLIENT")
        appendLine("  icmp: 'off'")
        appendLine()

        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: $SOCKS_HOST")
        appendLine("  udp: 'udp'")
        // Validated in A/B v2.8. Pipeline removes a serialized SOCKS5 handshake turn. TFO is
        // best-effort and transparently falls back to ordinary TCP on kernels that do not support it.
        appendLine("  pipeline: true")
        appendLine("  tcp-fastopen: true")
        appendLine()

        // HEV mapped DNS replies with synthetic A records and converts those destinations back
        // to domain-form SOCKS5 requests. That preserves sing-box domain routing without an
        // extra DNS round trip in the TUN -> SOCKS bridge.
        appendLine("mapdns:")
        appendLine("  address: $MAPPED_DNS")
        appendLine("  port: 53")
        appendLine("  network: 100.64.0.0")
        appendLine("  netmask: 255.192.0.0")
        appendLine("  cache-size: 16384")
        appendLine()

        appendLine("misc:")
        appendLine("  task-stack-size: 86016")
        appendLine("  tcp-buffer-size: 131072")
        appendLine("  udp-recv-buffer-size: 1048576")
        appendLine("  udp-copy-buffer-nums: 32")
        appendLine("  max-session-count: 0")
        appendLine("  connect-timeout: 10000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  log-level: error")
    }
}

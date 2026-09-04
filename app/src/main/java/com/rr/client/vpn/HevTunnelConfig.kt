package com.rr.client.vpn

/**
 * HEV native data-plane profile.
 *
 * HEV upstream ships an 8500-byte virtual TUN MTU in its reference config. Compared with the
 * common 1500-byte Android VPN MTU this reduces user-space TUN packet/syscall churn for TCP-heavy
 * traffic. Beta2 deliberately stays IPv4-only to match the real-device stable system-TUN baseline;
 * IPv6 can be enabled later as an independent compatibility change.
 *
 * Network Lab v2.8 may enable a benchmark-only latency candidate. That candidate changes only
 * HEV's loopback SOCKS5 client handshake (pipeline + TCP Fast Open); normal HEV keeps the proven
 * v2.5-v2.7 profile unchanged until real-device A/B data proves the candidate is beneficial.
 */
object HevTunnelConfig {
    const val MTU = 8500
    const val IPV4_CLIENT = "198.18.0.1"
    const val IPV4_PREFIX = 30
    const val MAPPED_DNS = "198.18.0.2"
    const val SOCKS_HOST = "127.0.0.1"

    fun build(socksPort: Int, latencyCandidate: Boolean = false): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $IPV4_CLIENT")
        appendLine("  icmp: 'off'")
        appendLine()

        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: $SOCKS_HOST")
        appendLine("  udp: 'udp'")
        if (latencyCandidate) {
            // Upstream HEV supports both knobs. Pipeline removes one serialized SOCKS5 handshake
            // turn; TCP Fast Open is best-effort and transparently falls back when unsupported.
            appendLine("  pipeline: true")
            appendLine("  tcp-fastopen: true")
        }
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

package com.rr.client.vpn

/**
 * HEV native data-plane profile.
 *
 * Real-device A/B testing through v2.8 established the production HEV profile used by RRBOX:
 * 8500-byte virtual TUN MTU, enlarged native buffers, SOCKS5 handshake pipelining and
 * best-effort client TCP Fast Open. The pipeline/TFO pair reduced HEV cold TLS/TTFB variance while
 * preserving its throughput and CPU advantage in the validated A/B path.
 * DNS now traverses the same bridge to sing-box and returns real upstream addresses. Native
 * mapdns is deliberately disabled: its volatile address map cannot survive engine changes.
 */
object HevTunnelConfig {
    const val MTU = 8500
    const val IPV4_CLIENT = "198.18.0.1"
    const val IPV4_PREFIX = 30
    // A resolver endpoint only, never an address pool. HevConfigAdapter handles this /32:53.
    const val DNS_ADDRESS = "198.18.0.2"
    const val SOCKS_HOST = "127.0.0.1"

    fun build(
        socksPort: Int,
        username: String? = null,
        password: String? = null
    ): String = buildString {
        require(socksPort in 1..65535)
        require((username == null) == (password == null))
        require(username == null || username.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(password == null || password.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        appendLine("tunnel:")
        appendLine("  mtu: $MTU")
        appendLine("  ipv4: $IPV4_CLIENT")
        appendLine("  icmp: 'off'")
        appendLine()

        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: $SOCKS_HOST")
        if (username != null && password != null) {
            appendLine("  username: '$username'")
            appendLine("  password: '$password'")
        }
        appendLine("  udp: 'udp'")
        // Validated in A/B v2.8. Pipeline removes a serialized SOCKS5 handshake turn. TFO is
        // best-effort and transparently falls back to ordinary TCP on kernels that do not support it.
        appendLine("  pipeline: true")
        appendLine("  tcp-fastopen: true")
        appendLine()

        // Omitted mapdns uses upstream HEV's default cache-size=0. UDP and TCP DNS follow
        // the ordinary SOCKS path; no new synthetic 100.64 addresses are issued or reused.

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

package com.rr.client.vpn

import java.net.InetAddress
import java.net.Inet4Address

/** The native control channel carries tokens only. Configuration JSON never enters a shell. */
internal object RootEngineProtocol {
    const val BINARY_NAME = "librrbox-root-engine.so"
    const val MAX_COMMAND_BYTES = 16_384
    const val STARTUP_MILLIS = 60_000L
    const val GRANT_MILLIS = 45_000L
    const val FD_MARKER = 'F'

    fun processStartTicks(stat: String, expectedPid: Int): Long {
        val opening = stat.indexOf('(')
        val closing = stat.lastIndexOf(')')
        require(opening > 0 && closing > opening && stat.substring(0, opening).trim().toIntOrNull() == expectedPid) {
            "Invalid app process identity"
        }
        // comm (field 2) may itself contain spaces and parentheses. Field 22 is starttime.
        val fields = stat.substring(closing + 1).trim().split(Regex("\\s+"))
        return fields.getOrNull(19)?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Missing app process start time")
    }

    fun arguments(binaryPath: String, socketName: String, uid: Int, pid: Int, startTicks: Long,
                  startedElapsedMillis: Long): List<String> {
        require(binaryPath.startsWith('/') && binaryPath.endsWith("/$BINARY_NAME"))
        require(binaryPath.none { it == '\u0000' || it == '\n' || it == '\r' })
        require(socketName.matches(Regex("rrbox-root-[0-9a-f]{16,32}")))
        require(uid > 0 && pid > 0 && startTicks > 0 && startedElapsedMillis >= 0)
        val startSeconds = startedElapsedMillis / 1000L
        val deadlineSeconds = startSeconds + STARTUP_MILLIS / 1000L
        val argv = listOf(binaryPath, "--socket", socketName, "--uid", uid.toString(),
            "--pid", pid.toString(), "--start", startTicks.toString(), "--deadline", deadlineSeconds.toString())
        // Android elapsedRealtime, /proc/uptime, and native CLOCK_BOOTTIME include suspend.
        // A root-manager grant delivered after cancellation cannot start a fresh session.
        val guard = "IFS=' ' read -r rrbox_uptime rrbox_unused < /proc/uptime || exit 125; " +
            "rrbox_now=\${rrbox_uptime%%.*}; " +
            "case \"\$rrbox_now\" in ''|*[!0-9]*) exit 125;; esac; " +
            "[ \"\$rrbox_now\" -ge $startSeconds ] && [ \"\$rrbox_now\" -le $deadlineSeconds ] || exit 124; "
        return listOf("su", "-c", guard + "exec " + argv.joinToString(" ", transform = ::quote))
    }

    fun configure(include: List<Int>, exclude: List<Int>, dns: List<String>, appUid: Int): String {
        require(include.isEmpty() || exclude.isEmpty()) { "Conflicting Root per-app policies" }
        fun uids(values: List<Int>) = values.filterNot { it == appUid }.distinct().sorted().also {
            require(it.size <= 256 && it.all { uid -> uid >= 0 }) { "Root 分应用策略最多支持 256 个 UID" }
        }
        val included = uids(include)
        val excluded = uids(exclude)
        require(include.isEmpty() || included.isNotEmpty()) { "Root include policy has no usable application UID" }
        val addresses = dns.map { numericAddress(it) ?: error("Invalid physical DNS address") }.distinct().sorted()
        require(addresses.size <= 16) { "Too many physical DNS servers" }
        val mode = if (include.isEmpty()) "all" else "include"
        fun csv(values: List<*>) = values.joinToString(",").ifEmpty { "-" }
        return "CONFIG $mode ${csv(included)} ${csv(excluded)} ${csv(addresses)}".also {
            require(it.length + 1 <= MAX_COMMAND_BYTES) { "Root UID policy is too large" }
        }
    }

    fun ownerCommand(protocol: Int, source: String, sourcePort: Int, destination: String,
                     destinationPort: Int): String? {
        if (protocol != 6 && protocol != 17 || sourcePort !in 1..65535 || destinationPort !in 1..65535) return null
        val src = numericAddress(source) ?: return null
        val dst = numericAddress(destination) ?: return null
        if ((':' in src) != (':' in dst)) return null
        return "OWNER $protocol $src $sourcePort $dst $destinationPort"
    }

    fun ownerUid(response: String): Int {
        if (response == "UNKNOWN") return -1
        require(response.matches(Regex("UID (0|[1-9][0-9]{0,9})"))) { "Malformed Root owner response" }
        return response.substring(4).toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("Root owner UID out of range")
    }

    fun numericAddress(value: String): String? {
        val address = value.substringBefore('%')
        if (address.isEmpty() || address.length > 45 || value.any { it.isWhitespace() || it == '\u0000' }) return null
        if (':' !in address) {
            val octets = address.split('.')
            return address.takeIf { octets.size == 4 && octets.all { octet ->
                octet.isNotEmpty() && octet.length <= 3 && octet.all { it in '0'..'9' } &&
                    (octet.length == 1 || octet[0] != '0') && (octet.toIntOrNull() ?: 256) <= 255
            } }
        }
        // This character gate plus a colon guarantees getByName only parses a literal;
        // a UID lookup must never issue DNS or leave a resolver thread behind.
        if (address.any { it !in "0123456789abcdefABCDEF:." }) return null
        return runCatching {
            val parsed = InetAddress.getByName(address)
            // Android may report one endpoint as ::ffff:a.b.c.d and the other as a.b.c.d.
            // They describe the same IPv4 tuple and must not lose package attribution.
            if (parsed is Inet4Address) parsed.hostAddress else address.lowercase()
        }.getOrNull()
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}

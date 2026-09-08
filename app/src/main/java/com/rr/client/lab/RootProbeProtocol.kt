package com.rr.client.lab

import java.io.ByteArrayOutputStream

/** Fixed, bundled executable only; no node data, imported text or UI input enters this command. */
internal object RootProbeCommand {
    const val BINARY_NAME = "librrbox-root-probe.so"
    const val OUTER_TIMEOUT_MILLIS = 60_000L
    // Leave time for the native probe's independent <=15 s watchdog before the host timeout.
    private const val GRANT_WINDOW_SECONDS = 43L

    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL in executable path" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    fun arguments(binaryPath: String, startedElapsedMillis: Long): List<String> {
        require(binaryPath.startsWith('/') && binaryPath.endsWith("/$BINARY_NAME")) {
            "Expected the bundled native probe's absolute path"
        }
        require(startedElapsedMillis >= 0L)
        val start = startedElapsedMillis / 1000L
        val deadline = start + GRANT_WINDOW_SECONDS
        // A late approval must not start a fresh native probe after the app has timed out.
        // /proc/uptime and Android elapsedRealtime both include suspend and ignore wall-clock
        // changes. Only shell builtins read this fixed kernel file; there is no PATH lookup.
        val command = "IFS=' ' read -r rrbox_uptime rrbox_unused < /proc/uptime || exit 125; " +
            "rrbox_now=\${rrbox_uptime%%.*}; " +
            "case \"\$rrbox_now\" in ''|*[!0-9]*) exit 125;; esac; " +
            "[ \"\$rrbox_now\" -ge $start ] && [ \"\$rrbox_now\" -le $deadline ] || exit 124; " +
            "exec ${quote(binaryPath)}"
        return listOf("su", "-c", command)
    }
}

internal data class RootProbeOutput(
    val text: String = "",
    val truncated: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null
)

/** Keep draining after the byte limit, so a noisy child cannot fill its pipe and stall. */
internal class RootProbeCapture(private val limitBytes: Int = 64 * 1024) {
    private val bytes = ByteArrayOutputStream()
    private var truncated = false
    private var finished = false
    private var error: String? = null

    init { require(limitBytes > 0) }

    @Synchronized
    fun append(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size)
        val keep = count.coerceAtMost(limitBytes - bytes.size())
        bytes.write(buffer, 0, keep)
        if (keep < count) truncated = true
    }

    @Synchronized
    fun finish(failure: String? = null) {
        finished = true
        error = failure?.take(256)
    }

    @Synchronized
    fun snapshot() = RootProbeOutput(bytes.toString("UTF-8"), truncated, finished, error)

    @Synchronized
    fun isFinished(): Boolean = finished
}

internal enum class RootProbeOutcome(val title: String) {
    PASSED("临时 TUN 检查通过"),
    FAILED("隔离检查未通过"),
    INCOMPLETE("报告不完整"),
    TIMED_OUT("测试超时"),
    UNAVAILABLE("无法启动测试")
}

internal data class RootProbeExecution(
    val stdout: RootProbeOutput = RootProbeOutput(),
    val stderr: RootProbeOutput = RootProbeOutput(),
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val launchError: String? = null,
    val elapsedMillis: Long = 0L
) {
    private val lines = stdout.text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    private val entries = lines.filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
    val values: Map<String, String> = entries.toMap()
    private val duplicateKeys = entries.map { it.first }.let { it.size != it.toSet().size }
    val complete: Boolean = stdout.finished && stderr.finished &&
        !stdout.truncated && !stderr.truncated && stdout.error == null && stderr.error == null &&
        !duplicateKeys && lines.lastOrNull() == COMPLETE_MARKER &&
        lines.count { it == COMPLETE_MARKER } == 1 &&
        REQUIRED_KEYS.all(values::containsKey) &&
        values["probe_version"] == "1" && values["network_verified"] == "NOT_TESTED"

    val outcome: RootProbeOutcome
        get() = when {
            timedOut || exitCode == 124 -> RootProbeOutcome.TIMED_OUT
            launchError != null -> RootProbeOutcome.UNAVAILABLE
            !complete -> RootProbeOutcome.INCOMPLETE
            exitCode == 0 && values["uid"] == "0" && values["euid"] == "0" &&
                CORE_CHECKS.all { values[it] == "PASS" } -> RootProbeOutcome.PASSED
            else -> RootProbeOutcome.FAILED
        }

    companion object {
        const val COMPLETE_MARKER = "RRBOX_ROOT_PROBE_COMPLETE"
        val CORE_CHECKS = listOf("root", "tun_create", "tun_down", "tun_unaddressed", "tun_cleanup")
        val REQUIRED_KEYS = CORE_CHECKS + listOf(
            "probe_version", "uid", "euid", "selinux_context", "proc_tcp", "proc_tcp6", "proc_udp", "proc_udp6",
            "sock_diag_tcp", "sock_diag_udp", "ip_transparent_v4", "ip_transparent_v6", "network_verified"
        )
    }
}

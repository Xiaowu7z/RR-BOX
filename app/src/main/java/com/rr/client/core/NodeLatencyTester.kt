package com.rr.client.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

sealed interface NodeLatencyState {
    data object Idle : NodeLatencyState
    data object Testing : NodeLatencyState
    data class Success(val millis: Long) : NodeLatencyState
    data object Timeout : NodeLatencyState
}

/**
 * Lightweight server Ping for the node list.
 *
 * This deliberately does not touch the running sing-box service, so latency
 * testing cannot destabilise the known-good VPN path. It invokes Android's
 * built-in ping binary without root. Results are server ICMP latency, not a
 * full proxy-protocol handshake benchmark.
 */
object NodeLatencyTester {
    private val timePattern = Regex("""time[=<]\s*([0-9.]+)\s*ms""", RegexOption.IGNORE_CASE)

    suspend fun ping(host: String, timeoutSeconds: Int = 3): NodeLatencyState = withContext(Dispatchers.IO) {
        val target = host.trim().removePrefix("[").removeSuffix("]")
        if (target.isBlank()) return@withContext NodeLatencyState.Timeout

        var process: Process? = null
        try {
            process = ProcessBuilder(
                "/system/bin/ping",
                "-c", "1",
                "-W", timeoutSeconds.coerceIn(1, 10).toString(),
                target
            )
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor((timeoutSeconds + 2).toLong(), TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return@withContext NodeLatencyState.Timeout
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }
            parsePingOutput(output) ?: NodeLatencyState.Timeout
        } catch (_: Throwable) {
            process?.destroyForcibly()
            NodeLatencyState.Timeout
        }
    }

    internal fun parsePingOutput(output: String): NodeLatencyState.Success? {
        val millis = timePattern.find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: return null
        return NodeLatencyState.Success(millis.coerceAtLeast(1.0).roundToLong())
    }
}

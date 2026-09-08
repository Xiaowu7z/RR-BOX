package com.rr.client.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootProbeProtocolTest {
    @Test
    fun commandQuotesApkPathAndGuardsDelayedAuthorization() {
        val path = "/data/app/O'Brien \$(id)/lib/arm64/${RootProbeCommand.BINARY_NAME}"
        val arguments = RootProbeCommand.arguments(path, 1_000_000L)
        assertEquals(listOf("su", "-c"), arguments.take(2))
        assertTrue(arguments.last().endsWith("exec '/data/app/O'\"'\"'Brien \$(id)/lib/arm64/librrbox-root-probe.so'"))
        assertTrue(arguments.last().contains("read -r rrbox_uptime rrbox_unused < /proc/uptime || exit 125"))
        assertTrue(arguments.last().contains("rrbox_now=\${rrbox_uptime%%.*}"))
        assertTrue(arguments.last().contains("[ \"\$rrbox_now\" -ge 1000 ] && [ \"\$rrbox_now\" -le 1043 ] || exit 124"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandRejectsAnotherExecutable() {
        RootProbeCommand.arguments("/system/bin/sh", 1_000_000L)
    }

    @Test
    fun expiredGrantExitsBeforeTryingToExecuteBundledPath() {
        // JVM CI on Linux can exercise the actual shell guard without su or any privilege.
        assumeTrue(File("/bin/sh").canExecute() && File("/proc/uptime").canRead())
        val uptimeSeconds = File("/proc/uptime").readText().substringBefore(' ').substringBefore('.').toLong()
        assumeTrue(uptimeSeconds > 60L)
        val command = RootProbeCommand.arguments(
            "/does-not-exist/${RootProbeCommand.BINARY_NAME}", (uptimeSeconds - 60L) * 1000L
        ).last()
        val process = ProcessBuilder("/bin/sh", "-c", command).start()
        try {
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertEquals(124, process.exitValue())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun onlyCompleteSuccessfulCoreChecksPassAndOptionalCapabilitiesStaySeparate() {
        val output = successfulOutput().replace("ip_transparent_v6=PASS", "ip_transparent_v6=UNSUPPORTED")
        val result = execution(output)
        assertEquals(RootProbeOutcome.PASSED, result.outcome)
        assertEquals("UNSUPPORTED", result.values["ip_transparent_v6"])
        assertEquals("NOT_TESTED", result.values["network_verified"])
    }

    @Test
    fun missingCompletionMissingChecksDuplicateKeysAndTrailingOutputNeverPass() {
        val variants = listOf(
            successfulOutput().replace(RootProbeExecution.COMPLETE_MARKER, ""),
            successfulOutput().replace("tun_cleanup=PASS\n", ""),
            "tun_cleanup=FAIL\n" + successfulOutput(),
            successfulOutput() + "unexpected output\n"
        )
        variants.forEach { output ->
            assertFalse(execution(output).complete)
            assertEquals(RootProbeOutcome.INCOMPLETE, execution(output).outcome)
        }
    }

    @Test
    fun timeoutFailedExitOrFailedCleanupNeverPassEvenWithCompletionMarker() {
        assertEquals(RootProbeOutcome.TIMED_OUT, execution(successfulOutput()).copy(timedOut = true).outcome)
        assertEquals(RootProbeOutcome.TIMED_OUT, execution(successfulOutput()).copy(exitCode = 124).outcome)
        assertEquals(RootProbeOutcome.FAILED, execution(successfulOutput()).copy(exitCode = 1).outcome)
        assertEquals(RootProbeOutcome.FAILED, execution(successfulOutput().replace("tun_cleanup=PASS", "tun_cleanup=FAIL")).outcome)
    }

    @Test
    fun incompatibleVersionOrClaimedNetworkValidationIsNotAccepted() {
        assertEquals(RootProbeOutcome.INCOMPLETE, execution(successfulOutput().replace("probe_version=1", "probe_version=2")).outcome)
        assertEquals(RootProbeOutcome.INCOMPLETE, execution(successfulOutput().replace("network_verified=NOT_TESTED", "network_verified=PASS")).outcome)
    }

    @Test
    fun unfinishedTruncatedOrUnreadableStreamNeverPasses() {
        val good = execution(successfulOutput())
        val variants = listOf(
            good.copy(stdout = good.stdout.copy(finished = false)),
            good.copy(stdout = good.stdout.copy(truncated = true)),
            good.copy(stderr = good.stderr.copy(truncated = true)),
            good.copy(stderr = good.stderr.copy(error = "read failed"))
        )
        variants.forEach { assertEquals(RootProbeOutcome.INCOMPLETE, it.outcome) }
    }

    @Test
    fun capturesRemainBoundedWhileContinuingToAcceptDrainedBytes() {
        val capture = RootProbeCapture(limitBytes = 17)
        repeat(1000) { capture.append(ByteArray(128) { 'x'.code.toByte() }, 128) }
        capture.finish()
        val result = capture.snapshot()
        assertEquals("x".repeat(17), result.text)
        assertTrue(result.truncated)
        assertTrue(result.finished)
    }

    private fun execution(output: String) = RootProbeExecution(
        stdout = RootProbeOutput(output, finished = true),
        stderr = RootProbeOutput(finished = true),
        exitCode = 0
    )

    companion object {
        fun successfulOutput(): String = buildString {
            appendLine("probe_version=1")
            appendLine("uid=0")
            appendLine("euid=0")
            RootProbeExecution.CORE_CHECKS.forEach { appendLine("$it=PASS") }
            listOf("selinux_context", "proc_tcp", "proc_tcp6", "proc_udp", "proc_udp6",
                "sock_diag_tcp", "sock_diag_udp", "ip_transparent_v4", "ip_transparent_v6"
            ).forEach { appendLine("$it=PASS") }
            appendLine("network_verified=NOT_TESTED")
            appendLine(RootProbeExecution.COMPLETE_MARKER)
        }
    }
}

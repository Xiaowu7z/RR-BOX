package com.rr.client.lab

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RootProbeProcessRunnerTest {
    @Test
    fun drainsBothPipesAndRequiresProcessExit() = runBlocking {
        val child = FakeProcess(exited = true, output = RootProbeProtocolTest.successfulOutput(), error = "optional diagnostic\n")
        val result = RootProbeProcessRunner(launch = { child }).run(BINARY_PATH)
        assertEquals(RootProbeOutcome.PASSED, result.outcome)
        assertEquals("optional diagnostic\n", result.stderr.text)
    }

    @Test
    fun timeoutReturnsEvenWhenProcessDestroyBlocks() = runBlocking {
        val releaseDestroy = CountDownLatch(1)
        val child = FakeProcess(output = RootProbeProtocolTest.successfulOutput(), destroyGate = releaseDestroy)
        try {
            val result = withTimeout(2000) {
                RootProbeProcessRunner(launch = { child }, timeoutMillis = 50).run(BINARY_PATH)
            }
            assertEquals(RootProbeOutcome.TIMED_OUT, result.outcome)
            assertTrue(child.destroyEntered.await(1, TimeUnit.SECONDS))
            assertEquals(1L, releaseDestroy.count)
        } finally {
            releaseDestroy.countDown()
        }
    }

    @Test
    fun cancellationTerminatesOnlyTheOwnedProcess() = runBlocking {
        val child = FakeProcess()
        val launched = AtomicBoolean(false)
        val job = launch {
            RootProbeProcessRunner(launch = { launched.set(true); child }).run(BINARY_PATH)
        }
        withTimeout(2000) { while (!launched.get()) delay(10) }
        job.cancelAndJoin()
        assertTrue(child.destroyEntered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun noisyOutputIsFullyDrainedButNeverReportedAsPassing() = runBlocking {
        val child = FakeProcess(exited = true, output = RootProbeProtocolTest.successfulOutput(), error = "x".repeat(100_000))
        val result = RootProbeProcessRunner(launch = { child }).run(BINARY_PATH)
        assertEquals(65536, result.stderr.text.length)
        assertTrue(result.stderr.finished)
        assertTrue(result.stderr.truncated)
        assertEquals(RootProbeOutcome.INCOMPLETE, result.outcome)
    }

    private class FakeProcess(
        exited: Boolean = false,
        output: String = "",
        error: String = "",
        private val destroyGate: CountDownLatch? = null
    ) : Process() {
        private val exited = AtomicBoolean(exited)
        private val stdout = ByteArrayInputStream(output.toByteArray())
        private val stderr = ByteArrayInputStream(error.toByteArray())
        private val stdin = ByteArrayOutputStream()
        val destroyEntered = CountDownLatch(1)

        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun getOutputStream(): OutputStream = stdin
        override fun exitValue(): Int = if (exited.get()) 0 else throw IllegalThreadStateException()
        override fun waitFor(): Int { while (!exited.get()) Thread.yield(); return 0 }
        override fun destroy() {
            destroyEntered.countDown()
            destroyGate?.await(3, TimeUnit.SECONDS)
            exited.set(true)
        }
    }

    private companion object {
        const val BINARY_PATH = "/data/app/com.rr.client/lib/arm64/librrbox-root-probe.so"
    }
}

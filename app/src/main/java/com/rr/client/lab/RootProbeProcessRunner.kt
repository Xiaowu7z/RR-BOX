package com.rr.client.lab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Owns only the su process started for this one fixed probe; never kills by name or UID. */
internal class RootProbeProcessRunner(
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val timeoutMillis: Long = RootProbeCommand.OUTER_TIMEOUT_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    suspend fun run(binaryPath: String): RootProbeExecution = withContext(Dispatchers.IO) {
        val startedElapsedMillis = elapsedRealtimeMillis()
        val stdout = RootProbeCapture()
        val stderr = RootProbeCapture()
        var process: Process? = null
        var timedOut = false
        var exitCode: Int? = null
        var launchError: String? = null
        try {
            coroutineContext.ensureActive()
            process = try {
                launch(RootProbeCommand.arguments(binaryPath, startedElapsedMillis))
            } catch (error: Exception) {
                launchError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(1024)
                null
            }
            val child = process
            if (child != null) {
                drain(child.inputStream, stdout, "stdout")
                drain(child.errorStream, stderr, "stderr")
                // We never send input to su or the probe. Its only input is the fixed command.
                runCatching { child.outputStream.close() }
                val remaining = (timeoutMillis - (elapsedRealtimeMillis() - startedElapsedMillis)).coerceAtLeast(1L)
                val finished = withTimeoutOrNull(remaining) {
                    while (elapsedRealtimeMillis() - startedElapsedMillis < timeoutMillis) {
                        coroutineContext.ensureActive()
                        exitCode = runCatching { child.exitValue() }.getOrNull()
                        if (exitCode != null && stdout.isFinished() && stderr.isFinished()) return@withTimeoutOrNull true
                        delay(25)
                    }
                    false
                }
                timedOut = finished != true
            }
        } finally {
            process?.let { child ->
                // Root managers may proxy the child, so its native watchdog is also mandatory.
                // Pipe close can block behind a native read: cleanup runs on a daemon, never Main
                // and never prolongs the host deadline. Snapshots below remain strictly bounded.
                Thread({
                    runCatching { child.destroy() }
                    runCatching { if (child.isAlive) child.destroyForcibly() }
                    runCatching { child.inputStream.close() }
                    runCatching { child.errorStream.close() }
                    runCatching { child.outputStream.close() }
                }, "rrbox-root-probe-close").apply { isDaemon = true; start() }
            }
        }
        RootProbeExecution(
            stdout = stdout.snapshot(),
            stderr = stderr.snapshot(),
            exitCode = exitCode,
            timedOut = timedOut,
            launchError = launchError,
            elapsedMillis = (elapsedRealtimeMillis() - startedElapsedMillis).coerceAtLeast(0L)
        )
    }

    private fun drain(stream: InputStream, capture: RootProbeCapture, name: String) {
        Thread({
            var failure: String? = null
            try {
                stream.use {
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (count > 0) capture.append(buffer, count)
                    }
                }
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            } finally {
                capture.finish(failure)
            }
        }, "rrbox-root-probe-$name").apply { isDaemon = true; start() }
    }
}

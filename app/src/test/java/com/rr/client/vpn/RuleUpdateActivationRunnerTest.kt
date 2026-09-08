package com.rr.client.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleUpdateActivationRunnerTest {
    private class Harness {
        var current = true
        var pointer = "old"
        var running: String? = "old"
        val events = mutableListOf<String>()
        var candidate: suspend Harness.() -> Unit = { running = "candidate" }
        var commit: suspend Harness.() -> Boolean = { pointer = "candidate"; true }
        var cleanup: suspend Harness.() -> Unit = { running = null }
        var restore: suspend Harness.() -> Unit = { running = "old" }

        suspend fun activate() = RuleUpdateActivationRunner.activate(
            isCurrent = { current },
            startCandidate = { events += "start"; candidate() },
            commitCandidate = { events += "commit"; commit() },
            stopCandidate = { events += "stop"; cleanup() },
            restorePrevious = { events += "restore"; restore() }
        )
    }

    @Test fun onlySuccessfulEngineStartupCanPublishCandidate() = runBlocking {
        val harness = Harness()
        assertEquals(RuleUpdateActivationRunner.Result.Activated, harness.activate())
        assertEquals(listOf("start", "commit"), harness.events)
        assertEquals("candidate", harness.pointer)
        assertEquals("candidate", harness.running)
    }

    @Test fun failureAfterPartialEngineStartupRestoresOldRuntimeWithoutPublishing() = runBlocking {
        // The Root helper, HEV forwarding process, or stable TUN can fail after core creation.
        for (engine in listOf("Root helper", "HEV forwarding process", "System TUN")) {
            val harness = Harness().apply {
                candidate = { running = "partial $engine"; error("$engine failed") }
            }
            val result = harness.activate()
            assertTrue(result is RuleUpdateActivationRunner.Result.Restored)
            assertEquals(listOf("start", "stop", "restore"), harness.events)
            assertEquals("old", harness.pointer)
            assertEquals("old", harness.running)
        }
    }

    @Test fun changedBaseVersionRestoresPreviousRuntime() = runBlocking {
        val harness = Harness().apply { commit = { false } }
        assertTrue(harness.activate() is RuleUpdateActivationRunner.Result.Restored)
        assertEquals(listOf("start", "commit", "stop", "restore"), harness.events)
        assertEquals("old", harness.pointer)
        assertEquals("old", harness.running)
    }

    @Test fun pointerWriteFailureRestoresPreviousRuntime() = runBlocking {
        val harness = Harness().apply { commit = { error("pointer fsync failed") } }
        assertTrue(harness.activate() is RuleUpdateActivationRunner.Result.Restored)
        assertEquals("old", harness.pointer)
        assertEquals("old", harness.running)
    }

    @Test fun unconfirmedRootCleanupDoesNotInstallAnotherDataPlane() = runBlocking {
        val harness = Harness().apply {
            candidate = { error("activation failed") }
            cleanup = { error("helper did not acknowledge rollback") }
        }
        val result = harness.activate()
        assertTrue(result is RuleUpdateActivationRunner.Result.Failed)
        assertEquals(listOf("start", "stop"), harness.events)
        assertEquals("old", harness.pointer)
    }

    @Test fun failureToRestoreDoesNotCommitFailedCandidate() = runBlocking {
        val harness = Harness().apply {
            candidate = { error("new startup failed") }
            restore = { error("old startup failed") }
        }
        val result = harness.activate()
        assertTrue(result is RuleUpdateActivationRunner.Result.Failed)
        assertTrue((result as RuleUpdateActivationRunner.Result.Failed).failure.message!!.contains("old startup failed"))
        assertEquals("old", harness.pointer)
    }

    @Test fun stopBeforeStartLeavesDataPlaneUntouched() = runBlocking {
        val harness = Harness().apply { current = false }
        assertEquals(RuleUpdateActivationRunner.Result.Superseded, harness.activate())
        assertTrue(harness.events.isEmpty())
    }

    @Test fun nodeSwitchDuringStartupCleansCandidateWithoutCommitOrRestore() = runBlocking {
        val harness = Harness().apply { candidate = { running = "candidate"; current = false } }
        assertEquals(RuleUpdateActivationRunner.Result.Superseded, harness.activate())
        assertEquals(listOf("start", "stop"), harness.events)
        assertEquals("old", harness.pointer)
        assertEquals(null, harness.running)
    }

    @Test fun stopDuringCleanupPreventsRestoringAnUnwantedConnection() = runBlocking {
        val harness = Harness().apply {
            candidate = { error("failed") }
            cleanup = { running = null; current = false }
        }
        assertEquals(RuleUpdateActivationRunner.Result.Superseded, harness.activate())
        assertEquals(listOf("start", "stop"), harness.events)
        assertEquals("old", harness.pointer)
    }

    @Test fun stopDuringRestoreCleansRestoredPlaneBeforeReturning() = runBlocking {
        val harness = Harness().apply {
            candidate = { error("failed") }
            restore = { running = "old"; current = false }
        }
        assertEquals(RuleUpdateActivationRunner.Result.Superseded, harness.activate())
        assertEquals(listOf("start", "stop", "restore", "stop"), harness.events)
        assertEquals(null, harness.running)
        assertEquals("old", harness.pointer)
    }

    @Test fun cancellationPropagatesToEngineOwnerWithoutStartingFallback() = runBlocking {
        val harness = Harness().apply { candidate = { throw CancellationException("user stopped") } }
        val result = runCatching { harness.activate() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(listOf("start"), harness.events)
        assertFalse(harness.events.contains("restore"))
        assertEquals("old", harness.pointer)
    }
}

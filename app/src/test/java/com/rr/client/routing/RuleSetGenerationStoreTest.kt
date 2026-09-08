package com.rr.client.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fault injection for publishing a complete, immutable pair of routing rule files. */
class RuleSetGenerationStoreTest {
    @Test
    fun failureWhileWritingSecondFilePreservesPreviousGeneration() = withDirectory { directory ->
        val store = newStore(directory)
        val previous = install(store, "old")

        val failure = runCatching {
            store.install { files ->
                files[0].writeText("rule:new")
                files[1].outputStream().use { output ->
                    output.write("rule:part".toByteArray())
                    throw IOException("second download interrupted")
                }
            }
        }

        assertTrue(failure.isFailure)
        assertSnapshotEquals(previous, store.current())
        assertPayload(previous, "old")
    }

    @Test
    fun completeFileValidationFailureDoesNotPublishNewGeneration() = withDirectory { directory ->
        val store = newStore(directory)
        val previous = install(store, "old")

        val failure = runCatching {
            store.install { files ->
                files[0].writeText("rule:new")
                files[1].writeText("broken compressed rule payload")
            }
        }

        assertTrue(failure.isFailure)
        assertSnapshotEquals(previous, store.current())
        assertPayload(previous, "old")
    }

    @Test
    fun pointerCommitFailurePreservesOldFilesAndPointer() = withDirectory { directory ->
        var rejectPointerCommit = false
        val store = newStore(directory, atomicMove = { source, target ->
            if (rejectPointerCommit && target.name == "active.properties") {
                throw IOException("simulated atomic pointer rename failure")
            }
            atomicMove(source, target)
        })
        val previous = install(store, "old")
        rejectPointerCommit = true

        assertTrue(runCatching { install(store, "new") }.isFailure)

        assertSnapshotEquals(previous, store.current())
        assertPayload(previous, "old")
        // Reopening the store must recover the same committed state as this instance.
        assertSnapshotEquals(previous, newStore(directory).current())
    }

    @Test
    fun damagedActivePointerFallsBackToPreviousCompleteGeneration() = withDirectory { directory ->
        val store = newStore(directory)
        val previous = install(store, "old")
        install(store, "new")
        File(directory, "active.properties").writeText("incomplete pointer")

        val recovered = newStore(directory).current()

        assertSnapshotEquals(previous, recovered)
        assertPayload(requireNotNull(recovered), "old")
    }

    @Test
    fun modifiedActivePayloadFallsBackToPreviousCompleteGeneration() = withDirectory { directory ->
        val store = newStore(directory)
        val previous = install(store, "old")
        val active = install(store, "new")
        active.files[1].writeText("rule:unexpected")

        val recovered = newStore(directory).current()

        assertSnapshotEquals(previous, recovered)
        assertPayload(requireNotNull(recovered), "old")
    }

    @Test
    fun identicalContentKeepsStablePathsAndReportsNoChange() = withDirectory { directory ->
        val store = newStore(directory)
        val original = store.install(updatedAtMillis = 100L) { files -> write(files, "same") }

        val repeated = store.install(updatedAtMillis = 200L) { files -> write(files, "same") }

        assertTrue(original.changed)
        assertFalse(repeated.changed)
        assertSnapshotEquals(original.snapshot, repeated.snapshot)
        // A successful refresh can update its check time without moving a running core's files.
        assertEquals(200L, repeated.snapshot.updatedAtMillis)
        assertSnapshotEquals(original.snapshot, store.current())
    }

    @Test
    fun firstInstallationIsInvisibleUntilBothFilesHavePassedValidation() = withDirectory { directory ->
        val store = newStore(directory)

        assertEquals(null, store.current())
        assertTrue(runCatching {
            store.install { files ->
                files[0].writeText("rule:first")
                throw IOException("second file unavailable")
            }
        }.isFailure)
        assertEquals(null, newStore(directory).current())

        assertPayload(install(store, "recovered"), "recovered")
    }

    @Test
    fun existingRulesAvoidOpeningBundledWriterWhenOnlyIfMissing() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "downloaded")

        val retained = store.install(onlyIfMissing = true) {
            throw AssertionError("bundled writer must not replace installed rules")
        }

        assertFalse(retained.changed)
        assertSnapshotEquals(original, retained.snapshot)
    }

    @Test
    fun previouslyReturnedRuntimePathsRemainReadableAcrossSeveralUpdates() = withDirectory { directory ->
        val store = newStore(directory)
        val inUse = install(store, "runtime")

        repeat(5) { install(store, "update-$it") }

        assertPayload(inUse, "runtime")
        assertTrue(inUse.files.all(File::isFile))
    }

    @Test
    fun externallyProtectedRuntimePathsSurviveReopeningAndUpdates() = withDirectory { directory ->
        val originalStore = newStore(directory)
        val runtime = install(originalStore, "runtime")
        install(originalStore, "one")
        install(originalStore, "two")
        val reopened = newStore(directory, protectedPaths = {
            runtime.files.map { it.absolutePath }.toSet()
        })

        repeat(4) { install(reopened, "later-$it") }

        assertPayload(runtime, "runtime")
    }

    @Test
    fun storageBudgetFailurePreservesCommittedGeneration() = withDirectory { directory ->
        val original = install(newStore(directory), "old")
        val constrained = newStore(directory, maxStoredBytes = 1L)

        assertTrue(runCatching { install(constrained, "new") }.isFailure)

        assertSnapshotEquals(original, constrained.current())
        assertPayload(original, "old")
        assertSnapshotEquals(original, newStore(directory).current())
    }

    @Test
    fun unreferencedGenerationsAreCollectedAfterReopening() = withDirectory { directory ->
        val originalStore = newStore(directory)
        val obsolete = install(originalStore, "obsolete")
        repeat(4) { install(originalStore, "intermediate-$it") }
        val previouslyCurrent = install(originalStore, "previous")
        val reopened = newStore(directory)

        val latest = install(reopened, "latest")

        assertTrue(obsolete.files.none(File::exists))
        assertPayload(previouslyCurrent, "previous")
        assertPayload(latest, "latest")
    }

    @Test
    fun concurrentReadersAndWritersNeverExposeMixedGenerations() = withDirectory { directory ->
        val store = newStore(directory)
        install(store, "initial")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val readers = List(3) {
                executor.submit(Callable {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(100) {
                        val snapshot = store.current()
                        assertNotNull(snapshot)
                        val files = requireNotNull(snapshot).files
                        // Read the returned paths after current() releases its lock: an update
                        // must never overwrite one half of an already published pair.
                        val first = files[0].readText()
                        Thread.yield()
                        val second = files[1].readText()
                        assertEquals(first, second)
                        assertTrue(first.startsWith("rule:"))
                        assertEquals(files[0].parentFile, files[1].parentFile)
                    }
                })
            }
            val writer = executor.submit(Callable {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                repeat(15) { generation ->
                    store.install { files ->
                        files[0].writeText("rule:update-$generation")
                        Thread.yield()
                        files[1].writeText("rule:update-$generation")
                    }
                }
            })
            start.countDown()
            writer.get(30, TimeUnit.SECONDS)
            readers.forEach { it.get(30, TimeUnit.SECONDS) }
            assertPayload(requireNotNull(store.current()), "update-14")
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun preparedCandidateDoesNotBecomeCurrentUntilActivation() = withDirectory { directory ->
        val store = newStore(directory)
        val previous = install(store, "old")
        val candidate = store.prepare(updatedAtMillis = 250L) { write(it, "new") }

        assertTrue(candidate.changed)
        assertSnapshotEquals(previous, store.current())
        assertSnapshotEquals(previous, newStore(directory).current())
        assertPayload(candidate.snapshot, "new")
        assertTrue(store.activate(candidate.snapshot, previous.generation))
        assertSnapshotEquals(candidate.snapshot, store.current())
        assertEquals(250L, store.current()?.updatedAtMillis)
    }

    @Test
    fun unchangedPreparationPreservesContentTimestamp() = withDirectory { directory ->
        val store = newStore(directory)
        val original = store.install(updatedAtMillis = 100L) { write(it, "same") }.snapshot

        val candidate = store.prepare(updatedAtMillis = 200L) { write(it, "same") }

        assertFalse(candidate.changed)
        assertSnapshotEquals(original, candidate.snapshot)
        assertEquals(100L, candidate.snapshot.updatedAtMillis)
        assertEquals(100L, newStore(directory).current()?.updatedAtMillis)
    }

    @Test
    fun firstPreparedCandidateIsNeverARecoveryFallback() = withDirectory { directory ->
        val store = newStore(directory)
        val candidate = store.prepare { write(it, "first") }.snapshot

        assertEquals(null, store.current())
        assertEquals(null, newStore(directory).current())
        File(directory, "active.properties").writeText("damaged pointer")
        assertEquals(null, newStore(directory).current())
        assertTrue(store.activate(candidate, null))
        assertPayload(requireNotNull(newStore(directory).current()), "first")
    }

    @Test
    fun staleCandidateCannotOverwriteNewerActivation() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "old")
        val first = store.prepare { write(it, "first") }.snapshot
        val second = store.prepare { write(it, "second") }.snapshot

        assertTrue(store.activate(first, original.generation))
        assertFalse(store.activate(second, original.generation))
        assertSnapshotEquals(first, store.current())
    }

    @Test
    fun rollbackUsesCompareAndSwapAndRestoresContentTimestamp() = withDirectory { directory ->
        val store = newStore(directory)
        val original = store.install(updatedAtMillis = 100L) { write(it, "old") }.snapshot
        val candidate = store.prepare(updatedAtMillis = 200L) { write(it, "new") }.snapshot
        assertTrue(store.activate(candidate, original.generation))
        val reopened = newStore(directory)
        val oldSnapshot = requireNotNull(reopened.snapshot(original.generation))

        assertTrue(reopened.activate(oldSnapshot, candidate.generation))
        assertSnapshotEquals(original, reopened.current())
        assertEquals(100L, reopened.current()?.updatedAtMillis)
        val later = install(reopened, "later")
        assertFalse(reopened.activate(oldSnapshot, candidate.generation))
        assertSnapshotEquals(later, reopened.current())
    }

    @Test
    fun activationRehashesCandidateEvenWhenSizeAndTimestampMatchCache() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "old")
        val candidate = store.prepare { write(it, "new") }.snapshot
        val changedFile = candidate.files[0]
        val timestamp = changedFile.lastModified()
        changedFile.writeText("rule:bad")
        assertTrue(changedFile.setLastModified(timestamp))

        assertTrue(runCatching { store.activate(candidate, original.generation) }.isFailure)
        assertSnapshotEquals(original, store.current())
    }

    @Test
    fun activationRejectsDamagedValidationMetadataBeforeChangingPointer() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "old")
        val candidate = store.prepare { write(it, "new") }.snapshot
        File(candidate.files[0].parentFile, "manifest.properties").writeText("schema=untrusted")

        assertTrue(runCatching { store.activate(candidate, original.generation) }.isFailure)
        assertSnapshotEquals(original, store.current())
    }

    @Test
    fun candidatePathsCannotBeSubstitutedByCaller() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "old")
        val candidate = store.prepare { write(it, "new") }.snapshot
        val forged = candidate.copy(files = original.files)

        assertTrue(runCatching { store.activate(forged, original.generation) }.isFailure)
        assertSnapshotEquals(original, store.current())
        assertEquals(null, store.snapshot("../active.properties"))
    }

    @Test
    fun failedPointerCommitCanRetryPersistedCandidateAfterReopen() = withDirectory { directory ->
        var reject = false
        val store = newStore(directory, atomicMove = { source, target ->
            if (reject && target.name == "active.properties") throw IOException("pointer commit failed")
            atomicMove(source, target)
        })
        val original = install(store, "old")
        val candidate = store.prepare(updatedAtMillis = 123L) { write(it, "new") }.snapshot
        reject = true

        assertTrue(runCatching { store.activate(candidate, original.generation) }.isFailure)
        val reopened = newStore(directory)
        assertSnapshotEquals(original, reopened.current())
        val restoredCandidate = requireNotNull(reopened.snapshot(candidate.generation))
        assertEquals(123L, restoredCandidate.updatedAtMillis)
        assertTrue(reopened.activate(restoredCandidate, original.generation))
        assertSnapshotEquals(candidate, reopened.current())
    }

    @Test
    fun pendingCandidateSurvivesCleanupAfterProcessReopen() = withDirectory { directory ->
        val store = newStore(directory)
        install(store, "old")
        val pending = store.prepare { write(it, "pending") }.snapshot
        val reopened = newStore(directory)

        repeat(5) { install(reopened, "later-$it") }

        assertEquals(listOf(pending.generation), reopened.preparedGenerations())
        assertPayload(pending, "pending")
        assertSnapshotEquals(pending, reopened.snapshot(pending.generation))
        File(directory, "active.properties").writeText("damaged pointer")
        assertTrue(newStore(directory).current()?.generation != pending.generation)
    }

    @Test
    fun discardingPendingCandidatePreservesIssuedPathsUntilProcessReopen() = withDirectory { directory ->
        val store = newStore(directory)
        install(store, "old")
        val pending = store.prepare { write(it, "pending") }.snapshot

        store.discardPrepared(pending.generation)
        assertTrue(store.preparedGenerations().isEmpty())
        assertPayload(pending, "pending")
        install(newStore(directory), "later")

        assertTrue(pending.files.none(File::exists))
    }

    @Test
    fun concurrentActivationsFromSameBaseHaveExactlyOneWinner() = withDirectory { directory ->
        val store = newStore(directory)
        val original = install(store, "old")
        val candidates = listOf("one", "two").map { payload -> store.prepare { write(it, payload) }.snapshot }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = candidates.map { candidate ->
                executor.submit(Callable {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    store.activate(candidate, original.generation)
                })
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(30, TimeUnit.SECONDS) })
            assertTrue(store.current()?.generation in candidates.map { it.generation })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun newStore(
        directory: File,
        protectedPaths: () -> Set<String> = { emptySet() },
        atomicMove: (File, File) -> Unit = ::atomicMove,
        maxStoredBytes: Long = 128L * 1024 * 1024
    ) = RuleSetGenerationStore(
        directory = directory,
        fileNames = listOf("domains.srs", "addresses.srs"),
        validate = { files ->
            require(files.size == 2)
            val payloads = files.map(File::readText)
            require(payloads.all { it.startsWith("rule:") }) { "invalid rule payload" }
            require(payloads.distinct().size == 1) { "incomplete generation" }
        },
        protectedPaths = protectedPaths,
        atomicMove = atomicMove,
        maxStoredBytes = maxStoredBytes
    )

    private fun atomicMove(source: File, target: File) {
        Files.move(
            source.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun install(store: RuleSetGenerationStore, payload: String) =
        store.install { files -> write(files, payload) }.snapshot

    private fun write(files: List<File>, payload: String) {
        files.forEach { it.writeText("rule:$payload") }
    }

    private fun assertPayload(snapshot: RuleSetGenerationStore.Snapshot, payload: String) {
        assertEquals(listOf("rule:$payload", "rule:$payload"), snapshot.files.map(File::readText))
    }

    private fun assertSnapshotEquals(
        expected: RuleSetGenerationStore.Snapshot,
        actual: RuleSetGenerationStore.Snapshot?
    ) {
        assertNotNull(actual)
        assertEquals(expected.generation, actual?.generation)
        assertEquals(expected.files.map { it.absolutePath }, actual?.files?.map { it.absolutePath })
    }

    private inline fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("rrbox-rule-generations").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

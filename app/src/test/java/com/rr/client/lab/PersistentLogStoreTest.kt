package com.rr.client.lab

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class PersistentLogStoreTest {
    private val directory = Files.createTempDirectory("rr-log-store-test").toFile()
    private val store = PersistentLogStore(flushDelayMillis = 60_000, pendingCapacity = 20_000)
    private class TestStorage(private val backing: LogStorage = MemoryLogStorage(capacity = 20_000)) : LogStorage by backing

    private suspend fun initialize(storage: LogStorage = TestStorage()) {
        store.initialize({ storage }, { directory })
        withTimeout(3000) { store.state.first { it.ready } }
    }
    @After fun cleanup() { store.closeForTests(); directory.deleteRecursively() }

    @Test fun defaultRetentionAndPaginationCoverAllStoredRowsWithoutLoadingHistoryIntoUi() = runBlocking {
        initialize()
        repeat(2500) { store.record("APP", "row-$it", it.toLong()) }
        var page = store.query(RRLogFilter(), null, 200)
        assertEquals(2000L, page.filteredCount)
        assertEquals(600, store.entries.value.size)
        val ids = mutableListOf<Long>()
        while (true) {
            ids.addAll(page.entries.map { it.id })
            val before = page.nextBeforeId ?: break
            page = store.query(RRLogFilter(), before, 200)
        }
        assertEquals(2000, ids.size)
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun unlimitedKeepsOldSearchMatchesAndExportIsFullChronologicalSanitizedHistory() = runBlocking {
        initialize()
        store.setRetentionLimit(0)
        store.record("ROUTE", "inactive-route-is-rejected")
        repeat(5200) { i -> store.record("APP", "record-$i ${if (i < 5) "needle password=secret-value" else "ordinary"}", 1_700_000_000_000L + 5200 - i) }
        assertEquals(5200L, store.query(RRLogFilter(), null, 200).filteredCount)
        assertFalse(store.entries.value.any { it.message.contains("needle") })
        assertEquals(5L, store.query(RRLogFilter(search = "needle"), null, 200).filteredCount)
        val bytes = ByteArrayOutputStream()
        assertEquals(5200L, store.export(bytes, RRLogFilter()))
        val text = bytes.toString("UTF-8")
        assertTrue(text.contains("2023-"))
        assertTrue(text.contains("170000000"))
        assertFalse(text.contains("secret-value"))
        assertTrue(text.indexOf("record-5199") < text.indexOf("record-0 "))
        val filtered = ByteArrayOutputStream()
        assertEquals(5L, store.export(filtered, RRLogFilter(search = "needle")))
        assertFalse(filtered.toString("UTF-8").contains("ordinary"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun reducingUnlimitedPrunesImmediatelyAndInvalidLimitDoesNotChangeSetting() = runBlocking {
        initialize()
        store.setRetentionLimit(0)
        repeat(2500) { store.record("APP", "row-$it") }
        store.setRetentionLimit(100)
        val page = store.query(RRLogFilter(), null, 200)
        assertEquals(100L, page.filteredCount)
        assertEquals("row-2499", page.entries.first().message)
        assertEquals("row-2400", page.entries.last().message)
        listOf(-1, 99, 1_000_001).forEach { limit ->
            try { store.setRetentionLimit(limit); fail("invalid limit accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(100, store.state.value.retentionLimit)
    }

    @Test fun clearDropsOldPendingButKeepsRecordsAcceptedAfterClearBoundary() = runBlocking {
        initialize()
        store.record("APP", "before-clear")
        val cleared = store.clear()
        store.record("APP", "after-clear")
        cleared.await()
        assertEquals(listOf("after-clear"), store.query(RRLogFilter(), null, 200).entries.map { it.message })
    }

    @Test fun clearDuringAnInflightWriteCannotResurrectOldEntries() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = TestStorage()
        initialize(object : LogStorage by data {
            override fun append(entries: List<LabLogEntry>): List<RRStoredLogEntry> {
                if (entries.any { it.message == "old-in-flight" }) {
                    entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                }
                return data.append(entries)
            }
        })
        store.record("APP", "old-in-flight")
        val pendingRead = async(Dispatchers.IO) { store.query(RRLogFilter(), null, 200) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val cleared = store.clear()
        store.record("APP", "after-clear")
        release.countDown()
        pendingRead.await(); cleared.await()
        assertEquals(listOf("after-clear"), store.query(RRLogFilter(), null, 200).entries.map { it.message })
        assertFalse(store.entries.value.any { it.message == "old-in-flight" })
    }

    @Test fun slowOutputDoesNotBlockWritesAndConcurrentClearCannotTruncateTheExportSnapshot() = runBlocking {
        initialize()
        repeat(900) { store.record("APP", "snapshot-$it", it.toLong()) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) { bytes.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); bytes.write(b, off, len)
            }
        }
        val exporting = async(Dispatchers.IO) { store.export(output, RRLogFilter()) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            withTimeout(2000) {
                store.record("APP", "after-snapshot")
                assertEquals(901L, store.query(RRLogFilter(), null, 200).filteredCount)
                store.clearHistory()
                assertEquals(0L, store.state.value.totalCount)
            }
        } finally { release.countDown() }
        assertEquals(900L, exporting.await())
        assertTrue(bytes.toString("UTF-8").contains("snapshot-0"))
        assertTrue(bytes.toString("UTF-8").contains("snapshot-899"))
        assertFalse(bytes.toString("UTF-8").contains("after-snapshot"))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun diskFailureIsReadyBoundedAndNeverReportsSubsequentMemoryOnlyChangesAsPersisted() = runBlocking {
        store.initialize({ throw IOException("disk unavailable") }, { directory })
        withTimeout(3000) { store.state.first { it.ready } }
        assertTrue(store.state.value.error.orEmpty().contains("2000"))
        repeat(3000) { store.record("APP", "fallback-$it") }
        assertEquals(2000L, store.query(RRLogFilter(), null, 200).filteredCount)
        repeat(2) {
            try { store.clearHistory(); fail("disk clear falsely succeeded") } catch (_: IllegalStateException) { }
            try { store.setRetentionLimit(100); fail("disk setting falsely succeeded") } catch (_: IllegalStateException) { }
        }
        assertEquals(0L, store.query(RRLogFilter(), null, 200).filteredCount)
    }

    @Test fun writeFailureFallsBackWithoutThrowingIntoTheLogProducer() = runBlocking {
        val data = TestStorage()
        initialize(object : LogStorage by data {
            override fun append(entries: List<LabLogEntry>): List<RRStoredLogEntry> {
                if (entries.isNotEmpty()) throw IOException("disk full token=do-not-store")
                return emptyList()
            }
        })
        store.record("APP", "still-visible token=hide-this")
        val page = store.query(RRLogFilter(), null, 200)
        assertEquals(1L, page.filteredCount)
        assertFalse(page.entries.single().message.contains("hide-this"))
        assertFalse(store.state.value.error.orEmpty().contains("do-not-store"))
    }

    @Test fun outputFailureAndCanceledStagingBothDeletePrivateTemporaryFiles() = runBlocking {
        initialize()
        repeat(800) { store.record("APP", "line-$it") }
        try {
            store.export(object : OutputStream() { override fun write(b: Int) { throw IOException("output closed") } }, RRLogFilter())
            fail("output failure hidden")
        } catch (_: IOException) { }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val second = PersistentLogStore(flushDelayMillis = 60_000)
        val visited = CountDownLatch(1)
        val release = CountDownLatch(1)
        val data = TestStorage()
        try {
            second.initialize({ object : LogStorage by data {
                override fun visitChronological(filter: RRLogFilter, visitor: (RRStoredLogEntry) -> Unit): Long =
                    data.visitChronological(filter) { entry ->
                        visited.countDown(); check(release.await(3, TimeUnit.SECONDS)); visitor(entry)
                    }
            } }, { directory })
            withTimeout(3000) { second.state.first { it.ready } }
            second.record("APP", "cancel-me")
            val exporting = launch(Dispatchers.IO) { second.export(ByteArrayOutputStream(), RRLogFilter()) }
            assertTrue(visited.await(3, TimeUnit.SECONDS))
            exporting.cancel(); release.countDown(); exporting.join()
            second.query(RRLogFilter(), null, 200)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { release.countDown(); second.closeForTests() }
    }

    @Test fun cancellationAfterSnapshotCompletionBeforeAwaitResumesDeletesTheStagingFile() = runBlocking {
        val producing = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val data = TestStorage()
        initialize(object : LogStorage by data {
            override fun visitChronological(filter: RRLogFilter, visitor: (RRStoredLogEntry) -> Unit): Long {
                producing.countDown()
                check(releaseSnapshot.await(3, TimeUnit.SECONDS))
                return data.visitChronological(filter, visitor)
            }
        })
        store.record("APP", "snapshot-completed")
        store.query(RRLogFilter(), null, 200)
        val continuations = ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { continuations.add(block) }
        }
        val bytes = ByteArrayOutputStream()
        val exporting = async(dispatcher) { store.export(bytes, RRLogFilter()) }
        continuations.remove().run() // Starts export and suspends awaiting the writer's result.
        assertTrue(producing.await(3, TimeUnit.SECONDS))
        releaseSnapshot.countDown()
        withTimeout(3000) { while (continuations.isEmpty()) delay(1) }
        assertEquals(1, directory.listFiles().orEmpty().size) // Result produced, caller not resumed.
        exporting.cancel()
        while (true) (continuations.poll() ?: break).run()
        exporting.join()
        assertEquals(0, bytes.size())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedDiskClearCannotReportSuccessOnASecondMemoryOnlyAttempt() = runBlocking {
        val data = TestStorage()
        initialize(object : LogStorage by data {
            override fun clear() { throw IOException("storage is read only") }
        })
        store.record("APP", "still-on-disk")
        store.query(RRLogFilter(), null, 200)
        repeat(2) {
            try { store.clearHistory(); fail("clear falsely succeeded") } catch (_: IllegalStateException) { }
        }
        assertEquals(1L, data.metadata().totalCount)
        assertEquals(0L, store.state.value.totalCount)
        assertNotNull(store.state.value.error)
    }
}

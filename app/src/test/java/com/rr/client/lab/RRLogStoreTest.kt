package com.rr.client.lab

import com.rr.client.security.SecretRedactor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RRLogStoreTest {
    private val store = PersistentLogStore(flushDelayMillis = 60_000)
    @After fun closeStore() = store.closeForTests()

    @Test fun redactRemovesCommonSecrets() {
        val raw = "uuid=123e4567-e89b-12d3-a456-426614174000 password=hello token=abc123 https://user:pass@example.com/path?key=secret"
        val redacted = SecretRedactor.redact(raw)
        listOf("123e4567-e89b-12d3-a456-426614174000", "hello", "abc123", "user:pass@", "key=secret")
            .forEach { assertFalse(redacted.contains(it)) }
    }

    @Test fun sevenHundredConnectionsRetainedBeyondTheRecentUiCache() = runBlocking {
        store.setConnectionLoggingActive(true)
        store.recordConnections((0..699).map { ConnectionRouteMessage(1_700_000_000_000L, "connection-$it") })
        val page = store.query(RRLogFilter(), null, 200)
        assertEquals(700L, page.filteredCount)
        assertEquals(600, store.entries.value.size)
        assertEquals("connection-699", page.entries.first().message)
        assertNull(store.state.value.error)
    }

    @Test fun ingressOverflowIsSeparateFromRetentionAndExplicitlyReported() = runBlocking {
        store.setConnectionLoggingActive(true)
        store.recordConnections((0..2999).map { ConnectionRouteMessage(1_700_000_000_000L, "connection-$it") })
        val page = store.query(RRLogFilter(), null, 200)
        assertEquals(2000L, page.filteredCount)
        assertTrue(page.entries.any { it.message == "connection-2999" })
        assertTrue(page.entries.any { it.message.contains("省略 952 条") })
        assertTrue(store.state.value.error.orEmpty().contains("待写队列上限 2048"))
    }

    @Test fun disablingCollectionPreservesAcceptedTailAndRejectsNewRecords() = runBlocking {
        store.setConnectionLoggingActive(true)
        store.recordConnections(listOf(ConnectionRouteMessage(1, "accepted-before-stop")))
        store.setConnectionLoggingActive(false)
        store.recordConnections(listOf(ConnectionRouteMessage(2, "new-after-stop")))
        store.record(ConnectionRouteLog.CHANNEL, "new-after-stop")
        assertEquals(listOf("accepted-before-stop"), store.query(RRLogFilter(), null, 200).entries.map { it.message })
    }
}

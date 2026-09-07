package com.rr.client.lab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class RRLogStoreTest {
    @After fun resetStore() {
        RRLogStore.setConnectionLoggingActive(false)
        RRLogStore.clear()
    }

    @Test
    fun redactRemovesCommonSecrets() {
        val raw = "uuid=123e4567-e89b-12d3-a456-426614174000 password=hello token=abc123 https://user:pass@example.com/path?key=secret"
        val redacted = RRLogStore.redact(raw)

        assertFalse(redacted.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(redacted.contains("hello"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("user:pass@"))
        assertFalse(redacted.contains("key=secret"))
        assertTrue(redacted.contains("<redacted>") || redacted.contains("<uuid>"))
    }

    @Test fun connectionBurstIsBoundedAndReportsDroppedRecords() = runBlocking {
        RRLogStore.clear()
        RRLogStore.setConnectionLoggingActive(true)
        val timestamp = System.currentTimeMillis()
        RRLogStore.recordConnections((0..699).map { ConnectionRouteMessage(timestamp, "connection-$it") })
        val entries = withTimeout(3_000) { RRLogStore.entries.first { it.isNotEmpty() } }
        assertEquals(600, entries.size)
        assertTrue(entries.any { it.message == "connection-699" })
        assertFalse(entries.any { it.message == "connection-0" })
        assertTrue(entries.any { it.message.contains("省略 100 条") })
    }

    @Test fun disablingCollectionCancelsPendingAndRejectsNewRecords() = runBlocking {
        RRLogStore.clear()
        RRLogStore.setConnectionLoggingActive(true)
        val message = ConnectionRouteMessage(System.currentTimeMillis(), "must-not-be-retained")
        RRLogStore.recordConnections(listOf(message))
        RRLogStore.setConnectionLoggingActive(false)
        RRLogStore.recordConnections(listOf(message))
        RRLogStore.record(ConnectionRouteLog.CHANNEL, message.message)
        delay(650)
        assertTrue(RRLogStore.entries.value.isEmpty())
    }
}

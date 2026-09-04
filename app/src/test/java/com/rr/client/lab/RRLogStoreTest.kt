package com.rr.client.lab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RRLogStoreTest {
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
}

package com.rr.client.subscription

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ImportLimitsTest {
    @Test fun exactByteLimitIsAccepted() { assertEquals("abcd", ImportLimits.readUtf8(ByteArrayInputStream("abcd".toByteArray()), 4)) }
    @Test(expected = IllegalArgumentException::class) fun oneByteOverLimitIsRejected() {
        ImportLimits.readUtf8(ByteArrayInputStream("abcde".toByteArray()), 4)
    }
    @Test fun handlesEmptyBody() { assertEquals("", ImportLimits.readUtf8(ByteArrayInputStream(byteArrayOf()), 4)) }
    @Test fun countsUtf8BytesNotCharacters() { assertEquals("中", ImportLimits.readUtf8(ByteArrayInputStream("中".toByteArray()), 3)) }
    @Test fun removesUtf8Bom() { assertEquals("x", ImportLimits.readUtf8(ByteArrayInputStream("\uFEFFx".toByteArray()), 4)) }
}

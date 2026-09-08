package com.rr.client.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootEngineProtocolTest {
    @Test fun processIdentityHandlesParenthesesInsideComm() {
        val fields = listOf("S") + List(18) { "0" } + "9876543210" + List(4) { "0" }
        assertEquals(9876543210L,
            RootEngineProtocol.processStartTicks("314 (worker ) (odd name)) ${fields.joinToString(" ")}", 314))
    }

    @Test fun processIdentityRejectsReusedOrMalformedIdentity() {
        rejects { RootEngineProtocol.processStartTicks("315 (app) S 0", 314) }
        rejects { RootEngineProtocol.processStartTicks("314 (app) S 0", 314) }
        rejects { RootEngineProtocol.processStartTicks("314 app S 0", 314) }
    }

    @Test fun launchUsesAbsoluteBootDeadlineAndQuotedFixedArguments() {
        val arguments = RootEngineProtocol.arguments("/data/app/rr'box/${RootEngineProtocol.BINARY_NAME}",
            "rrbox-root-0123456789abcdef", 10234, 456, 789L, 123_456L)
        assertEquals(listOf("su", "-c"), arguments.take(2))
        val command = arguments[2]
        assertTrue(command.contains("< /proc/uptime"))
        assertTrue(command.contains("-ge 123"))
        assertTrue(command.contains("-le 183"))
        assertTrue(command.contains("'--deadline' '183'"))
        assertTrue(command.contains("rr'\"'\"'box"))
        assertFalse(command.contains("date "))
    }

    @Test fun launchRejectsArbitraryExecutablesAndSocketTokens() {
        fun args(path: String, socket: String) = RootEngineProtocol.arguments(path, socket, 10100, 1, 1, 1)
        rejects { args("/bin/sh", "rrbox-root-0123456789abcdef") }
        rejects { args("/data/${RootEngineProtocol.BINARY_NAME}", "rrbox-root-0123456789abcdef;id") }
        rejects { args("/data/\n/${RootEngineProtocol.BINARY_NAME}", "rrbox-root-0123456789abcdef") }
    }

    @Test fun allAndIncludePoliciesPreserveUidMeaningAndExcludeSelf() {
        assertEquals("CONFIG all - - 1.1.1.1", RootEngineProtocol.configure(emptyList(), emptyList(), listOf("1.1.1.1"), 10001))
        assertEquals("CONFIG include 10002,10003 - -", RootEngineProtocol.configure(
            listOf(10003, 10001, 10002, 10003), emptyList(), emptyList(), 10001))
        assertEquals("CONFIG all - 10004 -", RootEngineProtocol.configure(emptyList(), listOf(10004, 10001), emptyList(), 10001))
    }

    @Test fun unusableIncludeDoesNotBecomeAllApplications() {
        rejects { RootEngineProtocol.configure(listOf(10001), emptyList(), emptyList(), 10001) }
        rejects { RootEngineProtocol.configure(listOf(10002), listOf(10003), emptyList(), 10001) }
        rejects { RootEngineProtocol.configure(listOf(-1), emptyList(), emptyList(), 10001) }
    }

    @Test fun excessivePoliciesAndDnsAreRejectedBeforeIpc() {
        rejects { RootEngineProtocol.configure((1..257).toList(), emptyList(), emptyList(), 50000) }
        rejects { RootEngineProtocol.configure(emptyList(), emptyList(), (1..17).map { "192.0.2.$it" }, 50000) }
        rejects { RootEngineProtocol.configure(emptyList(), emptyList(), listOf("dns.example.com"), 50000) }
    }

    @Test fun ownerRequestsAcceptOnlyNumericCompleteTuples() {
        assertEquals("OWNER 6 192.0.2.1 12345 203.0.113.2 443",
            RootEngineProtocol.ownerCommand(6, "192.0.2.1", 12345, "203.0.113.2", 443))
        assertEquals("OWNER 17 fe80::1 12345 2001:db8::2 53",
            RootEngineProtocol.ownerCommand(17, "fe80::1%wlan0", 12345, "2001:db8::2", 53))
        assertEquals("OWNER 6 192.0.2.1 12345 203.0.113.2 443",
            RootEngineProtocol.ownerCommand(6, "::ffff:192.0.2.1", 12345, "203.0.113.2", 443))
        assertNull(RootEngineProtocol.ownerCommand(1, "192.0.2.1", 1, "203.0.113.2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "example.com", 1, "203.0.113.2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "192.0.2.1\nSTOP", 1, "203.0.113.2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "999.0.0.1", 1, "203.0.113.2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "2001:::1", 1, "2001:db8::2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "192.0.2.1", 0, "203.0.113.2", 2))
        assertNull(RootEngineProtocol.ownerCommand(6, "192.0.2.1", 1, "::1", 2))
    }

    @Test fun ownerResponseDoesNotAcceptPartialOrOverflowedUid() {
        assertEquals(-1, RootEngineProtocol.ownerUid("UNKNOWN"))
        assertEquals(0, RootEngineProtocol.ownerUid("UID 0"))
        assertEquals(10234, RootEngineProtocol.ownerUid("UID 10234"))
        listOf("UID -1", "UID 2147483648", "UID 01", "UID 12 junk", "UID 1\nUID 2", "OK").forEach {
            rejects { RootEngineProtocol.ownerUid(it) }
        }
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return }
        throw AssertionError("Expected rejected protocol input")
    }
}

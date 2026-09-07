package com.rr.client.security

import org.junit.Assert.*
import org.junit.Test

class SecretRedactorTest {
    @Test fun redactsQuotedJsonKeys() {
        val text = SecretRedactor.redact("""{"password":"abcXYZ","uuid":"not-an-rfc-uuid","private_key":"mykey"}""")
        listOf("abcXYZ", "not-an-rfc-uuid", "mykey").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("<redacted>"))
    }
    @Test fun redactsEscapedJsonString() {
        val text = SecretRedactor.redact("""{"password":"abc\"def","server":"example.com"}""")
        assertFalse(text.contains("abc")); assertFalse(text.contains("def"))
        assertTrue(text.contains("example.com"))
    }
    @Test fun redactsSubscriptionPathAndUserInfo() {
        val text = SecretRedactor.redact("GET https://u:p@example.com/private-token/sub?other=hidden HTTP 500")
        assertFalse(text.contains("private-token")); assertFalse(text.contains("u:p")); assertFalse(text.contains("hidden"))
        assertTrue(text.contains("HTTP 500"))
    }
    @Test fun redactsEncodedNodePayload() { assertFalse(SecretRedactor.redact("vmess://c2VjcmV0#Mine").contains("c2VjcmV0")) }
    @Test fun keepsHarmlessDiagnostics() { assertEquals("RX=2048 ms=20", SecretRedactor.redact("RX=2048 ms=20")) }
    @Test fun redactsPlainAndQuerySecrets() {
        val text = SecretRedactor.redact("password=hello &token=xyz&auth=hidden")
        listOf("hello", "xyz", "hidden").forEach { assertFalse(text.contains(it)) }
    }
}

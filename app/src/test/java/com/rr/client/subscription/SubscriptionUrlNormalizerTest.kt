package com.rr.client.subscription

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionUrlNormalizerTest {
    @Test
    fun keepsExplicitHttps() {
        assertEquals(
            listOf("https://example.com/sub?token=x"),
            SubscriptionUrlNormalizer.candidates("https://example.com/sub?token=x")
        )
    }

    @Test
    fun keepsExplicitHttpIpAndPort() {
        assertEquals(
            listOf("http://192.0.2.8:8080/sub"),
            SubscriptionUrlNormalizer.candidates("http://192.0.2.8:8080/sub")
        )
    }

    @Test
    fun schemeLessIpv4TriesHttpsThenHttp() {
        assertEquals(
            listOf("https://192.0.2.8:8080/sub", "http://192.0.2.8:8080/sub"),
            SubscriptionUrlNormalizer.candidates("192.0.2.8:8080/sub")
        )
    }

    @Test
    fun bracketIpv6WithPortIsPreserved() {
        assertEquals(
            listOf("https://[2001:db8::1]:8080/sub", "http://[2001:db8::1]:8080/sub"),
            SubscriptionUrlNormalizer.candidates("[2001:db8::1]:8080/sub")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpSubscriptionScheme() {
        SubscriptionUrlNormalizer.candidates("ftp://example.com/sub")
    }
}

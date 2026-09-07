package com.rr.client.subscription

import org.junit.Assert.*
import org.junit.Test

class SubscriptionAddressTest {
    @Test fun acceptsQueryOnlySubscription() { assertTrue(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("https://example.com?token=a%2Bb")) }
    @Test fun handlesBomAndOuterWhitespace() { assertTrue(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress(" \uFEFFhttps://example.com/sub \n")) }
    @Test fun asksForRootHttpUrlInsteadOfGuessing() {
        assertTrue(SubscriptionUrlNormalizer.isAmbiguousHttpAddress("https://example.com/"))
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("https://example.com/"))
    }
    @Test fun preservesAuthenticatedProxyAndAsksForAuthenticatedResource() {
        assertFalse(SubscriptionUrlNormalizer.isAmbiguousHttpAddress("http://u:p@example.com:8080"))
        assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress("http://u:p@example.com:8080"))
        assertTrue(SubscriptionUrlNormalizer.isAmbiguousHttpAddress("https://u:p@example.com/sub"))
    }
    @Test fun rejectsInvalidPortsAndWhitespace() {
        listOf("http://example.com:0/sub", "http://example.com:65536/sub", "http://a b.com/sub", "https://example.com/sub\ntoken", "https://[broken]/sub").forEach {
            assertFalse(it, SubscriptionUrlNormalizer.looksLikeSubscriptionAddress(it))
        }
    }
    @Test fun neverMisclassifiesNodeOrConfigText() {
        listOf("vless://secret@example.com/path", "{\"server\":\"example.com\",\"path\":\"/sub\"}", "proxies:\n- name: Test", "YW55dGxzOi8vcHdkQGV4YW1wbGUuY29t").forEach {
            assertFalse(SubscriptionUrlNormalizer.looksLikeSubscriptionAddress(it))
            assertFalse(SubscriptionUrlNormalizer.isAmbiguousHttpAddress(it))
        }
    }
    @Test fun encodedTokensAreNotReencodedOrDecoded() {
        val url = "https://[2001:db8::1]:8443/sub?token=a%2Bb+c%26d"
        assertEquals(listOf(url), SubscriptionUrlNormalizer.candidates(url))
    }
}

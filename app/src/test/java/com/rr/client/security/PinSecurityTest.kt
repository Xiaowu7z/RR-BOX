package com.rr.client.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSecurityTest {
    @Test
    fun pinFormatIsStrictlyNumericAndFourToEightDigits() {
        assertTrue(PinSecurity.isValidFormat("1234"))
        assertTrue(PinSecurity.isValidFormat("12345678"))
        assertFalse(PinSecurity.isValidFormat("123"))
        assertFalse(PinSecurity.isValidFormat("123456789"))
        assertFalse(PinSecurity.isValidFormat("12a4"))
    }

    @Test
    fun credentialVerifiesWithoutStoringPlaintext() {
        val credential = PinSecurity.createCredential("2580")
        assertNotEquals("2580", credential.hashBase64)
        assertTrue(PinSecurity.verify("2580", credential.saltBase64, credential.hashBase64))
        assertFalse(PinSecurity.verify("2581", credential.saltBase64, credential.hashBase64))
    }

    @Test
    fun saltsProduceDifferentHashes() {
        val a = PinSecurity.createCredential("123456")
        val b = PinSecurity.createCredential("123456")
        assertNotEquals(a.saltBase64, b.saltBase64)
        assertNotEquals(a.hashBase64, b.hashBase64)
    }
}

package com.rr.client.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PinCredential(
    val saltBase64: String,
    val hashBase64: String
)

object PinSecurity {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun isValidFormat(pin: String): Boolean = pin.length in 4..8 && pin.all(Char::isDigit)

    fun createCredential(pin: String): PinCredential {
        require(isValidFormat(pin)) { "PIN 必须为 4-8 位数字" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        return PinCredential(
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            hashBase64 = Base64.getEncoder().encodeToString(hash)
        )
    }

    fun verify(pin: String, saltBase64: String?, hashBase64: String?): Boolean {
        if (!isValidFormat(pin) || saltBase64.isNullOrBlank() || hashBase64.isNullOrBlank()) return false
        return runCatching {
            val salt = Base64.getDecoder().decode(saltBase64)
            val expected = Base64.getDecoder().decode(hashBase64)
            MessageDigest.isEqual(expected, derive(pin, salt))
        }.getOrDefault(false)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

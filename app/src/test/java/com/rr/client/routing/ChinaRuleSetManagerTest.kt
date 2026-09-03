package com.rr.client.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChinaRuleSetManagerTest {
    @Test
    fun acceptsVersionFiveAndRejectsFutureVersion() {
        val supported = tempSrs(version = 5)
        val future = tempSrs(version = 6)
        try {
            assertTrue(ChinaRuleSetManager.isValidSrs(supported))
            assertFalse(ChinaRuleSetManager.isValidSrs(future))
        } finally {
            supported.delete()
            future.delete()
        }
    }

    @Test
    fun rejectsWrongMagic() {
        val file = File.createTempFile("rrbox-rule", ".srs")
        try {
            file.writeBytes(byteArrayOf(0x42, 0x41, 0x44, 0x05, 1, 2, 3, 4))
            assertFalse(ChinaRuleSetManager.isValidSrs(file))
        } finally {
            file.delete()
        }
    }

    private fun tempSrs(version: Int): File = File.createTempFile("rrbox-rule", ".srs").apply {
        writeBytes(
            byteArrayOf(
                0x53, 0x52, 0x53, version.toByte(),
                1, 2, 3, 4
            )
        )
    }
}

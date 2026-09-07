package com.rr.client.routing

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.DeflaterOutputStream

/** Container/size tests use real zlib data; semantic rule decoding belongs to libbox. */
class ChinaRuleSetManagerTest {
    @Test
    fun acceptsVersionFiveWithCompleteZlibAndRejectsFutureVersion() = withDirectory { directory ->
        val supported = container(File(directory, "supported.srs"), version = 5)
        val future = container(File(directory, "future.srs"), version = 6)

        SrsRuleSetValidation.validateContainer(supported)
        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(future) }.isFailure)
    }

    @Test
    fun rejectsMagicOnlyForgeryAndWrongMagic() = withDirectory { directory ->
        val forged = File(directory, "forged.srs").apply {
            writeBytes(byteArrayOf(0x53, 0x52, 0x53, 5, 1, 2, 3, 4))
        }
        val wrongMagic = container(File(directory, "wrong-magic.srs")).apply {
            val bytes = readBytes()
            bytes[0] = 0x42
            writeBytes(bytes)
        }

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(forged) }.isFailure)
        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(wrongMagic) }.isFailure)
    }

    @Test
    fun rejectsTruncatedStream() = withDirectory { directory ->
        val truncated = container(File(directory, "truncated.srs")).apply {
            writeBytes(readBytes().dropLast(2).toByteArray())
        }

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(truncated) }.isFailure)
    }

    @Test
    fun rejectsIncorrectZlibChecksum() = withDirectory { directory ->
        val damaged = container(File(directory, "checksum.srs")).apply {
            val bytes = readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            writeBytes(bytes)
        }

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(damaged) }.isFailure)
    }

    @Test
    fun rejectsGarbageAfterCompleteZlibStream() = withDirectory { directory ->
        val trailing = container(File(directory, "trailing.srs")).apply {
            appendBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(trailing) }.isFailure)
    }

    @Test
    fun rejectsEmptyInflatedPayload() = withDirectory { directory ->
        val empty = container(File(directory, "empty.srs"), expandedBytes = 0)

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(empty) }.isFailure)
    }

    @Test
    fun rejectsZeroTopLevelRules() = withDirectory { directory ->
        val noRules = container(File(directory, "zero-rules.srs"), payloadByte = 0)

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(noRules) }.isFailure)
    }

    @Test
    fun rejectsFileAboveEightMiBBeforeInflation() = withDirectory { directory ->
        val oversized = container(File(directory, "oversized.srs")).apply {
            RandomAccessFile(this, "rw").use { it.setLength(SrsRuleSetValidation.MAX_FILE_BYTES + 1) }
        }

        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(oversized) }.isFailure)
    }

    @Test
    fun streamedCopyAcceptsEightMiBAndRejectsFirstExtraByte() {
        val exactOutput = CountingOutput()
        val copied = SrsRuleSetValidation.copyBounded(
            RepeatingInput(SrsRuleSetValidation.MAX_FILE_BYTES), exactOutput
        )
        assertEquals(SrsRuleSetValidation.MAX_FILE_BYTES, copied)
        assertEquals(copied, exactOutput.bytes)

        val oversizedOutput = CountingOutput()
        val failure = runCatching {
            SrsRuleSetValidation.copyBounded(
                RepeatingInput(SrsRuleSetValidation.MAX_FILE_BYTES + 1), oversizedOutput
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(SrsRuleSetValidation.MAX_FILE_BYTES, oversizedOutput.bytes)
    }

    @Test
    fun streamedCopyHonorsCancellationBeforeReadingMoreData() {
        val cancellation = CancellationException("update cancelled")
        val output = CountingOutput()
        val failure = runCatching {
            SrsRuleSetValidation.copyBounded(RepeatingInput(100), output) { throw cancellation }
        }

        assertSame(cancellation, failure.exceptionOrNull())
        assertEquals(0L, output.bytes)
    }

    @Test
    fun acceptsSixtyFourMiBInflatedLimitAndRejectsOneExtraByte() = withDirectory { directory ->
        val maxExpanded = 64L * 1024 * 1024
        val exact = container(File(directory, "exact-expanded.srs"), expandedBytes = maxExpanded)
        val excessive = container(File(directory, "excessive-expanded.srs"), expandedBytes = maxExpanded + 1)

        assertTrue(excessive.length() < SrsRuleSetValidation.MAX_FILE_BYTES)
        SrsRuleSetValidation.validateContainer(exact)
        assertTrue(runCatching { SrsRuleSetValidation.validateContainer(excessive) }.isFailure)
    }

    @Test
    fun nativeCheckReceivesEveryLocalBinaryPathInOneConfiguration() = withDirectory { directory ->
        val files = listOf(
            container(File(directory, "中国 domains \"one\".srs")),
            container(File(directory, "addresses.srs"))
        )
        var checks = 0

        SrsRuleSetValidation.validate(files) { json ->
            checks++
            val config = JsonParser.parseString(json).asJsonObject
            val rules = config.getAsJsonObject("route").getAsJsonArray("rule_set")
            assertEquals(files.size, rules.size())
            assertEquals(files.map { it.absolutePath }, rules.map { it.asJsonObject["path"].asString })
            assertEquals(files.size, rules.map { it.asJsonObject["tag"].asString }.distinct().size)
            rules.forEach { rule ->
                assertEquals("local", rule.asJsonObject["type"].asString)
                assertEquals("binary", rule.asJsonObject["format"].asString)
            }
            assertFalse(config.has("inbounds"))
        }

        assertEquals(1, checks)
    }

    @Test
    fun nativeRuleDecoderFailureIsPropagated() = withDirectory { directory ->
        val files = listOf(container(File(directory, "domains.srs")), container(File(directory, "ips.srs")))
        val rejection = IllegalArgumentException("native SRS decoder rejected payload")

        val failure = runCatching {
            SrsRuleSetValidation.validate(files) { throw rejection }
        }

        assertSame(rejection, failure.exceptionOrNull())
    }

    @Test
    fun malformedContainerNeverReachesNativeCallback() = withDirectory { directory ->
        val good = container(File(directory, "good.srs"))
        val broken = File(directory, "broken.srs").apply { writeText("not an SRS file") }
        var nativeCalled = false

        val failure = runCatching {
            SrsRuleSetValidation.validate(listOf(good, broken)) { nativeCalled = true }
        }

        assertTrue(failure.isFailure)
        assertFalse(nativeCalled)
    }

    private fun container(file: File, version: Int = 5, expandedBytes: Long = 128, payloadByte: Byte = 1): File {
        file.outputStream().use { output ->
            output.write(byteArrayOf(0x53, 0x52, 0x53, version.toByte()))
            DeflaterOutputStream(output).use { compressed ->
                RepeatingInput(expandedBytes, payloadByte).use { it.copyTo(compressed) }
            }
        }
        return file
    }

    private class CountingOutput : OutputStream() {
        var bytes = 0L
            private set

        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }

    private class RepeatingInput(private var remaining: Long, private val value: Byte = 1) : InputStream() {
        override fun read(): Int = if (remaining <= 0) -1 else { remaining--; value.toInt() and 0xff }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining <= 0) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            buffer.fill(value, offset, offset + count)
            remaining -= count
            return count
        }
    }

    private inline fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("rrbox-srs-validation").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

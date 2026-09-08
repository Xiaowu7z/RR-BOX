package com.rr.client.routing

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

class RuleBundleManifestTest {
    @Test fun authenticatesBeforeReadingAndChecksEveryAssetDigest() {
        val result = verify(signed(payload()))
        assertEquals(VERSION, result.bundleVersion)
        assertEquals(2026090801L, result.policyVersion)
        assertEquals(RuleBundleManifest.FILE_NAMES, result.assets.keys)
        result.assets.values.forEach { asset ->
            asset.verify(DATA)
            rejects { asset.verify("wrong".toByteArray()) }
        }
    }

    @Test fun productionTrustDoesNotAcceptGeneratedTestSigner() {
        rejects { RuleBundleManifest.verify(signed(payload()), VERSION) }
    }

    @Test fun rejectsTamperedPayloadSignatureAndCertificate() {
        for (field in listOf("payload", "signature", "certificate")) {
            val envelope = JsonParser.parseString(String(signed(payload()))).asJsonObject
            val bytes = Base64.getDecoder().decode(envelope[field].asString)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            envelope.addProperty(field, Base64.getEncoder().encodeToString(bytes))
            rejects { verify(envelope.toString().toByteArray()) }
        }
    }

    @Test fun rejectsIncompatibleSchemaCoreAppAndChannelVersion() {
        listOf<(JsonObject) -> Unit>(
            { it.addProperty("schemaVersion", 2) },
            { it.addProperty("policySchemaVersion", 2) },
            { it.addProperty("coreVersion", "1.15.0") },
            { it.addProperty("minAppVersionCode", 101) },
            { it.addProperty("bundleVersion", VERSION - 1) },
            { it.addProperty("sourceCommit", "main") },
            { it.addProperty("publishedAt", "tomorrow") },
            { it.addProperty("downloadUrl", "https://example.com/evil") },
        ).forEach { change -> rejects { verify(signed(payload().apply(change))) } }
    }

    @Test fun rejectsMissingDuplicateUnsafeOversizedOrIncorrectAssets() {
        listOf<(JsonArray) -> Unit>(
            { it.remove(0) },
            { it[1] = it[0].deepCopy() },
            { it[0].asJsonObject.addProperty("fileName", "../config.json") },
            { it[0].asJsonObject.addProperty("size", RuleBundleManifest.MAX_POLICY_BYTES + 1) },
            { it[1].asJsonObject.addProperty("size", RuleBundleManifest.MAX_SRS_BYTES + 1) },
            { it[0].asJsonObject.addProperty("sha256", "0".repeat(63)) },
            { it[0].asJsonObject.addProperty("url", "https://example.com") },
        ).forEach { change ->
            rejects { verify(signed(payload().apply { change(getAsJsonArray("assets")) })) }
        }
    }

    @Test fun rejectsDuplicateFieldsMalformedUtf8NonIntegerAndTrailingJson() {
        val valid = payload().toString()
        listOf(
            valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            valid.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1e0"),
            valid + "{}",
        ).forEach { rejects { verify(signedBytes(it.toByteArray())) } }
        rejects { verify(signedBytes(byteArrayOf(0xc3.toByte(), 0x28))) }
        rejects { verify(ByteArray(RuleBundleManifest.MAX_MANIFEST_BYTES + 1)) }
    }

    @Test fun channelCannotSupplyPathsUrlsOrNonIntegerVersions() {
        assertEquals(VERSION, RuleBundleManifest.parseChannel("""{"schemaVersion":1,"bundleVersion":$VERSION}""".toByteArray()))
        listOf(
            """{"schemaVersion":1,"bundleVersion":"$VERSION"}""",
            """{"schemaVersion":1,"bundleVersion":-1}""",
            """{"schemaVersion":1,"bundleVersion":1.5}""",
            """{"schemaVersion":1,"bundleVersion":1,"bundleVersion":2}""",
            """{"schemaVersion":1,"bundleVersion":1,"url":"https://example.com"}""",
        ).forEach { rejects { RuleBundleManifest.parseChannel(it.toByteArray()) } }
        assertTrue(RuleBundleManifest.manifestUrl(VERSION).endsWith("/rules-v1-$VERSION/bundle-manifest.json"))
        assertEquals(3, RuleBundleManifest.manifestUrls(VERSION).size)
        assertTrue(RuleBundleManifest.assetUrls(VERSION, "rrbox-policy.json")[1]
            .endsWith("@rules-channel/bundles/$VERSION/rrbox-policy.json"))
        rejects { RuleBundleManifest.assetUrl(VERSION, "../../elsewhere") }
        rejects { RuleBundleManifest.assetUrls(VERSION, "../../elsewhere") }
    }

    private fun payload(): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("bundleVersion", VERSION)
        addProperty("policyVersion", 2026090801L)
        addProperty("publishedAt", "2026-09-08T00:00:00Z")
        addProperty("sourceCommit", "a".repeat(40))
        addProperty("minAppVersionCode", 100)
        addProperty("policySchemaVersion", 1)
        addProperty("coreVersion", "1.14.0")
        add("assets", JsonArray().apply {
            RuleBundleManifest.FILE_NAMES.forEach { name -> add(JsonObject().apply {
                addProperty("fileName", name)
                addProperty("size", DATA.size)
                addProperty("sha256", RuleBundleManifest.sha256(DATA))
            }) }
        })
    }

    private fun signed(payload: JsonObject) = signedBytes(payload.toString().toByteArray())
    private fun signedBytes(payload: ByteArray): ByteArray = JsonObject().apply {
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(payload) }.sign()
        addProperty("schemaVersion", 1)
        addProperty("payload", Base64.getEncoder().encodeToString(payload))
        addProperty("signature", Base64.getEncoder().encodeToString(signature))
        addProperty("certificate", Base64.getEncoder().encodeToString(certificate))
    }.toString().toByteArray()

    private fun verify(bytes: ByteArray) = RuleBundleManifest.verifyWithCertificatePin(
        bytes, VERSION, 100, RuleBundleManifest.sha256(certificate),
    )
    private fun rejects(block: () -> Unit) { assertTrue("Unsafe manifest must fail closed", runCatching(block).isFailure) }

    companion object {
        private const val VERSION = 3419782534801L
        private val DATA = "signed test bytes".toByteArray()
        private lateinit var directory: File
        private lateinit var key: PrivateKey
        private lateinit var certificate: ByteArray

        @JvmStatic @BeforeClass fun fixtureSigner() {
            directory = Files.createTempDirectory("rrbox-manifest-test-").toFile()
            val keystore = File(directory, "fixture.p12")
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/keytool").absolutePath,
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=RRBOX generated unit test fixture", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keystore.absolutePath,
                "-storepass", "test-only-password", "-keypass", "test-only-password",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            val store = KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, "test-only-password".toCharArray()) }
            }
            key = store.getKey("fixture", "test-only-password".toCharArray()) as PrivateKey
            certificate = store.getCertificate("fixture").encoded
        }

        @JvmStatic @AfterClass fun cleanFixture() { directory.deleteRecursively() }
    }
}

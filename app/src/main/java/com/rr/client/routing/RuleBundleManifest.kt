package com.rr.client.routing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** Only signed, versioned data is accepted; remote documents cannot choose URLs or executable config. */
object RuleBundleManifest {
    const val MAX_MANIFEST_BYTES = 128 * 1024
    const val MAX_POLICY_BYTES = 1024 * 1024L
    const val MAX_SRS_BYTES = 8 * 1024 * 1024L
    const val SIGNER_SHA256 = "fe1368cf16ee9e8b56199655d0b1e2606a6ec9b8f3d4ac5e16e8cf66e180d816"
    const val CORE_VERSION = "1.14.0"
    val FILE_NAMES: Set<String> = linkedSetOf("rrbox-policy.json", "geosite-geolocation-cn.srs", "geoip-cn.srs")
    val CHANNEL_URLS: List<String> = listOf(
        "https://raw.githubusercontent.com/Xiaowu7z/RR-BOX/rules-channel/channel.json",
        "https://testingcf.jsdelivr.net/gh/Xiaowu7z/RR-BOX@rules-channel/channel.json",
    )
    private val versionPattern = Regex("[1-9][0-9]{0,15}")
    private val digestPattern = Regex("[0-9a-f]{64}")

    data class Asset(val fileName: String, val size: Long, val sha256: String) {
        fun verify(bytes: ByteArray) {
            require(bytes.size.toLong() == size && RuleBundleManifest.sha256(bytes) == sha256) {
                "规则文件校验失败：$fileName"
            }
        }
    }

    class VerifiedBundle internal constructor(
        val bundleVersion: Long,
        val policyVersion: Long,
        val publishedAt: String,
        val sourceCommit: String,
        val assets: Map<String, Asset>,
    )

    fun parseChannel(bytes: ByteArray): Long {
        require(bytes.size <= 1024) { "规则频道内容过大" }
        val channel = strictObject(bytes)
        channel.exactFields("schemaVersion", "bundleVersion")
        require(channel.positiveLong("schemaVersion") == 1L) { "不支持的规则频道格式" }
        return channel.version("bundleVersion")
    }

    fun manifestUrl(bundleVersion: Long): String = assetUrl(bundleVersion, "bundle-manifest.json")

    fun manifestUrls(bundleVersion: Long): List<String> = assetUrls(bundleVersion, "bundle-manifest.json")

    fun assetUrls(bundleVersion: Long, fileName: String): List<String> = listOf(
        "https://raw.githubusercontent.com/Xiaowu7z/RR-BOX/rules-channel/bundles/$bundleVersion/$fileName",
        "https://testingcf.jsdelivr.net/gh/Xiaowu7z/RR-BOX@rules-channel/bundles/$bundleVersion/$fileName",
        assetUrl(bundleVersion, fileName),
    )

    fun assetUrl(bundleVersion: Long, fileName: String): String {
        require(versionPattern.matches(bundleVersion.toString())) { "无效的规则版本" }
        require(fileName in FILE_NAMES || fileName == "bundle-manifest.json") { "不支持的规则文件" }
        return "https://github.com/Xiaowu7z/RR-BOX/releases/download/rules-v1-$bundleVersion/$fileName"
    }

    fun verify(envelopeBytes: ByteArray, expectedBundleVersion: Long, appVersionCode: Int = 100): VerifiedBundle =
        verifyWithCertificatePin(envelopeBytes, expectedBundleVersion, appVersionCode, SIGNER_SHA256)

    /** Test seam: production callers always use [verify] and the certificate baked into this APK. */
    internal fun verifyWithCertificatePin(
        envelopeBytes: ByteArray,
        expectedBundleVersion: Long,
        appVersionCode: Int,
        certificateSha256: String,
    ): VerifiedBundle {
        require(envelopeBytes.size <= MAX_MANIFEST_BYTES) { "规则清单过大" }
        val envelope = strictObject(envelopeBytes)
        envelope.exactFields("schemaVersion", "payload", "signature", "certificate")
        require(envelope.positiveLong("schemaVersion") == 1L) { "不支持的签名清单格式" }
        val payload = decodeBase64(envelope.string("payload"), 64 * 1024)
        val signatureBytes = decodeBase64(envelope.string("signature"), 1024)
        val certificateBytes = decodeBase64(envelope.string("certificate"), 8192)
        require(sha256(certificateBytes) == certificateSha256) { "规则签名证书不可信" }
        val certificateInput = ByteArrayInputStream(certificateBytes)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateInput) as X509Certificate
        require(certificateInput.available() == 0 && certificate.publicKey.algorithm == "RSA") {
            "无效的规则签名证书"
        }
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initVerify(certificate.publicKey)
            update(payload)
        }
        require(signature.verify(signatureBytes)) { "规则签名验证失败" }

        // Interpret version, compatibility and file paths only after authenticating the original payload bytes.
        val manifest = strictObject(payload)
        manifest.exactFields(
            "schemaVersion", "bundleVersion", "policyVersion", "publishedAt", "sourceCommit",
            "minAppVersionCode", "policySchemaVersion", "coreVersion", "assets",
        )
        require(manifest.positiveLong("schemaVersion") == 1L && manifest.positiveLong("policySchemaVersion") == 1L) {
            "规则格式需要升级 RRBOX"
        }
        val bundleVersion = manifest.version("bundleVersion")
        require(bundleVersion == expectedBundleVersion) { "规则频道与清单版本不一致" }
        require(manifest.positiveLong("minAppVersionCode") <= appVersionCode.toLong()) { "规则需要更新版本的 RRBOX" }
        require(manifest.string("coreVersion") == CORE_VERSION) { "规则不兼容当前核心" }
        val policyVersion = manifest.version("policyVersion")
        val publishedAt = manifest.string("publishedAt")
        require(publishedAt.length <= 40) { "无效的规则发布时间" }
        Instant.parse(publishedAt)
        val sourceCommit = manifest.string("sourceCommit")
        require(Regex("[0-9a-f]{40}").matches(sourceCommit)) { "无效的规则来源版本" }
        val array = manifest.get("assets")
        require(array is JsonArray && array.size() == FILE_NAMES.size) { "规则文件集合不完整" }
        val assets = linkedMapOf<String, Asset>()
        for (element in array) {
            require(element is JsonObject) { "无效的规则文件描述" }
            element.exactFields("fileName", "size", "sha256")
            val fileName = element.string("fileName")
            require(fileName in FILE_NAMES && fileName !in assets) { "重复或不支持的规则文件" }
            val size = element.positiveLong("size")
            val limit = if (fileName == "rrbox-policy.json") MAX_POLICY_BYTES else MAX_SRS_BYTES
            require(size <= limit) { "规则文件过大：$fileName" }
            val digest = element.string("sha256")
            require(digestPattern.matches(digest)) { "无效的规则文件摘要" }
            assets[fileName] = Asset(fileName, size, digest)
        }
        return VerifiedBundle(bundleVersion, policyVersion, publishedAt, sourceCommit, assets)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeBase64(value: String, maxBytes: Int): ByteArray {
        require(value.length <= ((maxBytes + 2) / 3) * 4) { "签名字段过大" }
        return Base64.getDecoder().decode(value).also {
            require(it.isNotEmpty() && it.size <= maxBytes && Base64.getEncoder().encodeToString(it) == value) {
                "无效的签名字段"
            }
        }
    }

    private fun strictObject(bytes: ByteArray): JsonObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = readValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT && result is JsonObject) { "无效的规则清单 JSON" }
            return result
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 6) { "规则清单嵌套过深" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(!has(key)) { "规则清单包含重复字段" }
                    add(key, readValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) { add(readValue(reader, depth + 1)) }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(versionPattern.matches(number)) { "规则清单只接受正整数字段" }
                JsonPrimitive(number.toLong())
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> error("无效的规则清单 JSON")
        }
    }

    private fun JsonObject.exactFields(vararg expected: String) {
        require(keySet() == expected.toSet()) { "规则清单字段不完整或不受支持" }
    }

    private fun JsonObject.string(key: String): String {
        val value = get(key)
        require(value is JsonPrimitive && value.isString) { "无效的规则清单字段：$key" }
        return value.asString
    }

    private fun JsonObject.positiveLong(key: String): Long {
        val value = get(key)
        require(value is JsonPrimitive && value.isNumber && versionPattern.matches(value.asString)) {
            "无效的规则清单数字：$key"
        }
        return value.asLong
    }

    private fun JsonObject.version(key: String): Long = positiveLong(key)
}

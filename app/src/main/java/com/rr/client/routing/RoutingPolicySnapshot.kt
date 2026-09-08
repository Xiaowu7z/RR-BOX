package com.rr.client.routing

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Collections

/**
 * One immutable, data-only policy used by both routing and DNS compilation.
 * Engine actions, DNS servers, interception scope and outbound definitions stay in the APK.
 */
class RoutingPolicySnapshot private constructor(
    val schemaVersion: Int,
    val ruleVersion: Long,
    val publishedAt: String,
    val description: String,
    domainRules: List<DomesticRoutingPolicy.DomainRule>,
    proxyPackageGroups: List<List<String>>,
    directIpExceptions: List<String>
) {
    val domainRules: List<DomesticRoutingPolicy.DomainRule> = immutable(domainRules.map {
        it.copy(suffixes = immutable(it.suffixes), domains = immutable(it.domains))
    })
    val proxyPackageGroups: List<List<String>> = immutable(proxyPackageGroups.map(::immutable))
    val directIpExceptions: List<String> = immutable(directIpExceptions)

    override fun equals(other: Any?): Boolean = other is RoutingPolicySnapshot &&
        schemaVersion == other.schemaVersion && ruleVersion == other.ruleVersion &&
        publishedAt == other.publishedAt && description == other.description &&
        domainRules == other.domainRules && proxyPackageGroups == other.proxyPackageGroups &&
        directIpExceptions == other.directIpExceptions

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + ruleVersion.hashCode()
        result = 31 * result + publishedAt.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + domainRules.hashCode()
        result = 31 * result + proxyPackageGroups.hashCode()
        return 31 * result + directIpExceptions.hashCode()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_BYTES = 1024 * 1024
        const val BUNDLED_RULE_VERSION = 2026090801L
        private const val MAX_LIST_ITEMS = 4096
        private const val MAX_TOTAL_ITEMS = 16000
        private val rootKeys = setOf("schemaVersion", "ruleVersion", "publishedAt", "description",
            "domainRules", "proxyPackageGroups", "directIpExceptions")
        private val ruleKeys = setOf("id", "destination", "suffixes", "domains")
        private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val labelPattern = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val singleLabelSuffixes = setOf("cn", "xn--fiqs8s", "xn--fiqz9s")

        /**
         * Emergency baseline for callers without Android assets; intentionally versioned
         * independently from the canonical rrbox-policy.json asset / downloaded policy.
         * Routine policy releases update JSON and reviewed native fixture expectations only.
         */
        private val fallback by lazy {
            RoutingPolicySnapshot(
                SCHEMA_VERSION, BUNDLED_RULE_VERSION, "2026-09-08T00:00:00Z",
                "内置分流规则：保留中国服务直连、海外服务代理及 TikTok / BIGO 应用规则。",
                DomesticRoutingPolicy.domainRules,
                listOf(TikTokAppPolicy.proxyPackages, BigoAppPolicy.proxyPackages),
                DomesticRoutingPolicy.observedMainlandIpv4Exceptions
            )
        }

        fun bundled(): RoutingPolicySnapshot = fallback

        fun parse(bytes: ByteArray): RoutingPolicySnapshot {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "分流规则文件大小无效" }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return parse(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        }

        fun parse(json: String): RoutingPolicySnapshot {
            require(json.isNotEmpty() && json.length <= MAX_BYTES &&
                json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "分流规则文件大小无效" }
            val root = readStrictJson(json)
            require(root.isJsonObject) { "分流规则必须是 JSON 对象" }
            val data = root.asJsonObject
            require(data.keySet() == rootKeys) { "分流规则包含未知字段或缺少必要字段" }
            require(integer(data, "schemaVersion") == SCHEMA_VERSION.toLong()) { "不支持的分流规则格式" }
            val version = integer(data, "ruleVersion")
            require(version > 0) { "分流规则版本无效" }
            val publishedAt = string(data, "publishedAt", 40)
            require(publishedAt.endsWith("Z")) { "分流规则发布时间必须为 UTC" }
            Instant.parse(publishedAt)
            val description = string(data, "description", 2048)
            require(description.none { it.code < 32 && it != '\n' && it != '\t' }) {
                "分流规则说明包含控制字符"
            }

            var totalItems = 0
            fun strings(value: JsonElement, validate: (String) -> Boolean): List<String> {
                require(value.isJsonArray && value.asJsonArray.size() <= MAX_LIST_ITEMS) { "分流规则列表过大或格式无效" }
                val items = value.asJsonArray.map {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "分流规则条目必须是文本" }
                    it.asString.also { item -> require(validate(item)) { "分流规则包含无效条目" } }
                }
                totalItems += items.size
                require(totalItems <= MAX_TOTAL_ITEMS) { "分流规则条目过多" }
                require(items.distinct().size == items.size) { "分流规则列表包含重复条目" }
                return items
            }

            val domainData = data.get("domainRules")
            require(domainData.isJsonArray && domainData.asJsonArray.size() in 1..128) { "域名规则数量无效" }
            val domainRules = domainData.asJsonArray.map { element ->
                require(element.isJsonObject && element.asJsonObject.keySet() == ruleKeys) { "域名规则字段无效" }
                val rule = element.asJsonObject
                val id = string(rule, "id", 64)
                require(idPattern.matches(id)) { "域名规则 ID 无效" }
                val destination = when (string(rule, "destination", 6)) {
                    "DIRECT" -> DomesticRoutingPolicy.Destination.DIRECT
                    "PROXY" -> DomesticRoutingPolicy.Destination.PROXY
                    else -> throw IllegalArgumentException("域名规则目标无效")
                }
                val suffixes = strings(rule.get("suffixes")) { validDomain(it, suffix = true) }
                val domains = strings(rule.get("domains")) { validDomain(it, suffix = false) }
                require(suffixes.isNotEmpty() || domains.isNotEmpty()) { "域名规则不得匹配所有流量" }
                DomesticRoutingPolicy.DomainRule(id, destination, suffixes, domains)
            }
            require(domainRules.map { it.id }.distinct().size == domainRules.size) { "域名规则 ID 重复" }
            // Keep the app-owned priority contract: known international services must be
            // considered before domestic suffixes, China IP rules and the final fallback.
            var directSeen = false
            domainRules.forEach {
                if (it.destination == DomesticRoutingPolicy.Destination.DIRECT) directSeen = true
                else require(!directSeen) { "海外域名规则必须位于国内规则之前" }
            }

            val packageData = data.get("proxyPackageGroups")
            require(packageData.isJsonArray && packageData.asJsonArray.size() <= 128) { "应用规则数量无效" }
            val packages = packageData.asJsonArray.map { group ->
                strings(group) { it.length <= 255 && packagePattern.matches(it) && it != "com.rr.client" }
                    .also { require(it.isNotEmpty()) { "应用规则不能为空" } }
            }
            require(packages.flatten().distinct().size == packages.sumOf { it.size }) { "应用身份重复" }
            val ips = strings(data.get("directIpExceptions"), ::validHostCidr)
            return RoutingPolicySnapshot(SCHEMA_VERSION, version, publishedAt, description, domainRules, packages, ips)
        }

        private fun string(data: JsonObject, name: String, maxLength: Int): String {
            val value = data.get(name)
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) { "分流规则文本字段无效" }
            return value.asString.also { require(it.isNotEmpty() && it.length <= maxLength) { "分流规则文本长度无效" } }
        }

        private fun integer(data: JsonObject, name: String): Long {
            val value = data.get(name)
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber &&
                value.toString().matches(Regex("[0-9]{1,19}"))) { "分流规则版本必须是整数" }
            return value.asString.toLongOrNull() ?: throw IllegalArgumentException("分流规则版本超出范围")
        }

        private fun validDomain(host: String, suffix: Boolean): Boolean {
            if (host.length !in 1..253) return false
            if (!host.contains('.')) return suffix && host in singleLabelSuffixes
            val labels = host.split('.')
            return labels.all(labelPattern::matches) && labels.last().any { it in 'a'..'z' }
        }

        /** Literal syntax only. Never call InetAddress.getByName on downloaded data. */
        private fun validHostCidr(cidr: String): Boolean {
            val pieces = cidr.split('/')
            if (pieces.size != 2) return false
            val address = pieces[0]
            if (pieces[1] == "32") {
                val bytes = address.split('.')
                return bytes.size == 4 && bytes.all {
                    it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
                }
            }
            if (pieces[1] != "128" || address.isEmpty() || address.contains('%') || address.contains('.')) return false
            if (!address.all { it == ':' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
            if (address.contains(":::")) return false
            val compression = address.indexOf("::")
            if (compression != address.lastIndexOf("::")) return false
            val groups = if (compression >= 0) {
                val left = address.substring(0, compression).takeIf { it.isNotEmpty() }?.split(':').orEmpty()
                val right = address.substring(compression + 2).takeIf { it.isNotEmpty() }?.split(':').orEmpty()
                (left + right).also { if (it.size >= 8) return false }
            } else address.split(':').also { if (it.size != 8) return false }
            return groups.all { it.length in 1..4 }
        }

        /** Gson's tree parser accepts duplicate fields; reject them before creating the tree. */
        private fun readStrictJson(json: String): JsonElement {
            var nodes = 0
            JsonReader(StringReader(json)).use { reader ->
                reader.strictness = Strictness.STRICT
                fun read(depth: Int): JsonElement {
                    require(depth <= 8 && ++nodes <= 40000) { "分流规则结构过大或嵌套过深" }
                    return when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                require(!has(key)) { "分流规则包含重复字段" }
                                add(key, read(depth + 1))
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                            reader.beginArray()
                            while (reader.hasNext()) add(read(depth + 1))
                            reader.endArray()
                        }
                        JsonToken.STRING -> JsonPrimitive(reader.nextString())
                        JsonToken.NUMBER -> {
                            val raw = reader.nextString()
                            require(raw.matches(Regex("[0-9]{1,19}"))) { "分流规则数值无效" }
                            JsonPrimitive(raw.toLongOrNull() ?: throw IllegalArgumentException("分流规则数值超出范围"))
                        }
                        else -> throw IllegalArgumentException("分流规则 JSON 类型无效")
                    }
                }
                return read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) { "分流规则尾部包含额外内容" } }
            }
        }

        private fun <T> immutable(items: List<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    }
}

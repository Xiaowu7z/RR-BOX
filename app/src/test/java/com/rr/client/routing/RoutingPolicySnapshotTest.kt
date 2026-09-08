package com.rr.client.routing

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicySnapshotTest {
    private fun asset(): File = listOf(
        File("src/main/assets/rules/rrbox-policy.json"),
        File("app/src/main/assets/rules/rrbox-policy.json")
    ).first { it.isFile }

    // Parser boundary fixtures use the frozen baseline, so maintenance of a newer policy
    // does not require rewriting syntax tests that mention baseline rule IDs or packages.
    private fun json(): JsonObject = JsonParser.parseString(
        GsonBuilder().setPrettyPrinting().create().toJson(RoutingPolicySnapshot.bundled())
    ).asJsonObject
    private fun parse(edit: JsonObject.() -> Unit): RoutingPolicySnapshot =
        RoutingPolicySnapshot.parse(json().apply(edit).toString())

    private fun rejected(edit: JsonObject.() -> Unit) {
        assertThrows(Exception::class.java) { parse(edit) }
    }

    @Test
    fun assetMayAdvanceIndependentlyButCannotChangeAnAlreadyIssuedVersion() {
        val fromAsset = RoutingPolicySnapshot.parse(asset().readBytes())
        val bundled = RoutingPolicySnapshot.bundled()
        assertEquals(bundled.schemaVersion, fromAsset.schemaVersion)
        assertTrue("Canonical asset cannot predate the emergency baseline", fromAsset.ruleVersion >= bundled.ruleVersion)
        if (fromAsset.ruleVersion == bundled.ruleVersion) {
            assertEquals("An issued rule version must remain immutable", bundled, fromAsset)
            assertEquals(bundled.hashCode(), fromAsset.hashCode())
        }
    }

    @Test
    fun aNewerJsonPolicyCanChangeRulesWithoutChangingCompiledBaseline() {
        val baseline = RoutingPolicySnapshot.bundled()
        val newer = parse {
            addProperty("ruleVersion", baseline.ruleVersion + 1)
            addProperty("description", "更新独立 JSON 规则")
            getAsJsonArray("domainRules")[0].asJsonObject.getAsJsonArray("domains").add("new-policy.example")
        }
        assertTrue(newer.ruleVersion > baseline.ruleVersion)
        assertTrue(newer.domainRules.first().domains.contains("new-policy.example"))
        assertFalse(baseline.domainRules.first().domains.contains("new-policy.example"))
    }

    @Test
    fun unknownFieldsCannotInjectCoreActionsResolversOutboundsOrRootCommands() {
        for (field in listOf("outbounds", "dns", "action", "rootCommand", "include_package")) {
            rejected { addProperty(field, "unexpected") }
            rejected { getAsJsonArray("domainRules")[0].asJsonObject.addProperty(field, "unexpected") }
        }
        for (field in json().keySet()) rejected { remove(field) }
    }

    @Test
    fun invalidAndDuplicateJsonCannotSilentlyChangePolicy() {
        val text = GsonBuilder().setPrettyPrinting().create().toJson(json())
        val bad = listOf(
            text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),
            text.replaceFirst("\"id\":", "\"id\": \"first\", \"id\":"),
            text + "{}", "/* comment */$text", text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 1.0"),
            text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 1e0"),
            text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": null"),
            text.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": true")
        )
        bad.forEach { candidate -> assertThrows(Exception::class.java) { RoutingPolicySnapshot.parse(candidate) } }
    }

    @Test
    fun malformedUtf8OversizedFilesAndExcessiveNestingAreRejected() {
        assertThrows(Exception::class.java) { RoutingPolicySnapshot.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { RoutingPolicySnapshot.parse(ByteArray(RoutingPolicySnapshot.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { RoutingPolicySnapshot.parse("[".repeat(10) + "1" + "]".repeat(10)) }
        rejected { add("directIpExceptions", JsonArray().apply { repeat(4097) { add("192.0.2.1/32") } }) }
    }

    @Test
    fun schemaVersionIntegerVersionAndUtcPublicationAreRequired() {
        rejected { addProperty("schemaVersion", 2) }
        rejected { addProperty("ruleVersion", 0) }
        rejected { addProperty("ruleVersion", -1) }
        rejected { addProperty("ruleVersion", "2026090801") }
        rejected { addProperty("publishedAt", "2026-02-30T00:00:00Z") }
        rejected { addProperty("publishedAt", "2026-09-08T00:00:00+00:00") }
        rejected { addProperty("description", "bad\u0000text") }
    }

    @Test
    fun domainNamesAreBoundedExactAsciiLabelsWithNoWildcardUrlOrIp() {
        for (host in listOf("*.example.com", ".example.com", "example.com.", "EXAMPLE.com",
            "https://example.com", "example.com:443", "example..com", "-a.example.com", "a-.example.com",
            "bad_name.example.com", "192.0.2.1", "com", "中国.cn", "a".repeat(64) + ".com")) {
            rejected { getAsJsonArray("domainRules")[0].asJsonObject.add("suffixes", JsonArray().apply { add(host) }) }
        }
        val valid = parse {
            getAsJsonArray("domainRules")[0].asJsonObject.add("suffixes", JsonArray().apply {
                add("cn"); add("xn--fiqs8s"); add("xn--fiqz9s"); add("xn--bcher-kva.example")
            })
        }
        assertEquals(4, valid.domainRules.first().suffixes.size)
    }

    @Test
    fun domainRulesCannotBeEmptyDuplicateOrPlaceDomesticRulesBeforeInternationalRules() {
        rejected { getAsJsonArray("domainRules")[0].asJsonObject.apply {
            add("suffixes", JsonArray()); add("domains", JsonArray())
        } }
        rejected { getAsJsonArray("domainRules")[0].asJsonObject.addProperty("id", "china-suffix") }
        rejected { getAsJsonArray("domainRules")[0].asJsonObject.addProperty("destination", "DIRECT") }
        rejected { getAsJsonArray("domainRules")[0].asJsonObject.addProperty("destination", "reject") }
        rejected { getAsJsonArray("domainRules")[0].asJsonObject.getAsJsonArray("suffixes").add("tiktok.com") }
        rejected { add("domainRules", JsonArray()) }
    }

    @Test
    fun packageRulesRemainExactAndCannotCaptureRrboxItself() {
        for (value in listOf("com.example.*", "com.rr.client", "com..example", "com.example:other", "com.example/")) {
            rejected { add("proxyPackageGroups", JsonArray().apply { add(JsonArray().apply { add(value) }) }) }
        }
        rejected { getAsJsonArray("proxyPackageGroups").add(JsonArray()) }
        rejected { getAsJsonArray("proxyPackageGroups").add(JsonArray().apply { add("sg.bigo.live") }) }
        val result = parse { add("proxyPackageGroups", JsonArray().apply { add(JsonArray().apply { add("com.example") }) }) }
        assertEquals(listOf(listOf("com.example")), result.proxyPackageGroups)
    }

    @Test
    fun ipExceptionsAcceptOnlyExactIpv4OrIpv6HostsWithoutNameResolution() {
        for (value in listOf("192.0.2.1/32", "2001:db8::1/128", "2001:db8:0:0:0:0:0:1/128")) {
            val result = parse { add("directIpExceptions", JsonArray().apply { add(value) }) }
            assertEquals(listOf(value), result.directIpExceptions)
        }
        for (value in listOf("0.0.0.0/0", "192.0.2.1/24", "192.0.2.1", "256.1.1.1/32",
            "192.00.2.1/32", "example.com/32", "2001:db8::/64", "2001:db8:::1/128",
            "2001:db8::1::2/128", "2001:db8:0:0:0:0:0::1/128", "fe80::1%wlan0/128")) {
            rejected { add("directIpExceptions", JsonArray().apply { add(value) }) }
        }
    }

    @Test
    fun emptyPackageAndIpListsAreAllowedWithoutCreatingMatchAllRules() {
        val result = parse { add("proxyPackageGroups", JsonArray()); add("directIpExceptions", JsonArray()) }
        assertTrue(result.proxyPackageGroups.isEmpty())
        assertTrue(result.directIpExceptions.isEmpty())
        assertFalse(result.domainRules.isEmpty())
    }

    @Test
    fun parsedAndBundledSnapshotsAreDeeplyImmutable() {
        for (snapshot in listOf(RoutingPolicySnapshot.parse(asset().readBytes()), RoutingPolicySnapshot.bundled())) {
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.domainRules as MutableList).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.domainRules.first().suffixes as MutableList).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.proxyPackageGroups as MutableList).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.proxyPackageGroups.first() as MutableList).clear()
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (snapshot.directIpExceptions as MutableList).clear()
            }
        }
    }
}

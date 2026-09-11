package com.rr.client.storage

import com.rr.client.routing.AppNodeBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppNodeBindingsCodecTest {
    private val telegram = AppNodeBinding("org.telegram.messenger", "hk-node")

    @Test fun legacyAbsentPreferenceIsAnEmptyExplicitRouteList() {
        assertTrue(AppNodeBindingsCodec.decode(null).isEmpty())
        assertTrue(AppNodeBindingsCodec.decode("[]").isEmpty())
    }

    @Test fun missingEnabledDefaultsToTrueAndDisabledRulesRoundTrip() {
        assertEquals(listOf(telegram), AppNodeBindingsCodec.decode(
            """[{"packageName":"org.telegram.messenger","nodeId":"hk-node"}]"""
        ))
        val rules = listOf(telegram.copy(enabled = false), AppNodeBinding("com.openai.chatgpt", "la-node"))
        assertEquals(rules.sortedBy { it.packageName }, AppNodeBindingsCodec.decode(AppNodeBindingsCodec.encode(rules)))
    }

    @Test fun malformedPreferenceCannotSilentlyRemoveAnExplicitExit() {
        listOf("", "null", "{}", "[null]", "[{", "[] []", "[/*comment*/]",
            """[{"packageName":"org.telegram.messenger","nodeId":"hk-node","enabled":"false"}]""",
            """[{"packageName":"org.telegram.messenger","nodeId":null}]""",
            """[{"packageName":"org.telegram.messenger"}]""",
            """[{"packageName":"org.telegram.messenger","nodeId":"hk-node","enabled":false,"enabled":true}]"""
        ).forEach { raw ->
            try {
                AppNodeBindingsCodec.decode(raw)
                fail("Must reject malformed explicit routes: $raw")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("配置损坏"))
            }
        }
    }

    @Test fun duplicateAppsSelfPackagesAndBlankTargetsAreRejectedBeforeSaving() {
        listOf(listOf(telegram, telegram.copy(nodeId = "other")),
            listOf(telegram.copy(packageName = "com.rr.client")),
            listOf(telegram.copy(packageName = " telegram ")),
            listOf(telegram.copy(nodeId = " ")),
            listOf(telegram.copy(nodeId = " hk-node"))
        ).forEach { rules ->
            try {
                AppNodeBindingsCodec.encode(rules)
                fail("Invalid explicit routes must not be saved")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

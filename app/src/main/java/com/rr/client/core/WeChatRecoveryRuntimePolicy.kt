package com.rr.client.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.routing.WeChatIpv6RecoveryPolicy

/** Keep optional compatibility rules in the canonical cache, then gate only the live copy. */
object WeChatRecoveryRuntimePolicy {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun apply(configJson: String, enabled: Boolean): String {
        if (enabled) return configJson
        val root = JsonParser.parseString(configJson).asJsonObject
        val route = root.getAsJsonObject("route") ?: return configJson
        val rules = route.getAsJsonArray("rules") ?: return configJson
        val retained = rules.filterNot { it.isJsonObject && isRecoveryRule(it.asJsonObject) }
        if (retained.size == rules.size()) return configJson
        route.add("rules", JsonArray().apply { retained.forEach(::add) })
        return gson.toJson(root)
    }

    private fun isRecoveryRule(rule: JsonObject): Boolean {
        fun string(key: String): String? = rule.get(key)?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString
        if (string("action") != "route" || string("outbound") != "direct" ||
            string("type") != "logical" || string("mode") != "and") return false
        val host = string("override_address") ?: return false
        if (!WeChatIpv6RecoveryPolicy.isReviewedHost(host)) return false
        val parts = rule.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        val exactDomain = parts.any { part ->
            if (!part.isJsonObject) false else {
                val domains = part.asJsonObject.get("domain")?.takeIf { it.isJsonArray }?.asJsonArray
                domains?.size() == 1 && domains[0].isJsonPrimitive && domains[0].asString == host
            }
        }
        val ipv6 = parts.any { part ->
            part.isJsonObject && part.asJsonObject.get("ip_version")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == 6
            } == true
        }
        return exactDomain && ipv6
    }
}

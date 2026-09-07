package com.rr.client.core

import com.google.gson.JsonParser

/** An unchanged download can still need applying after recovery to a previous rule generation. */
object RuleSetRuntimePolicy {
    fun needsReload(configJson: String?, geositePath: String, geoipPath: String): Boolean = runCatching {
        val paths = JsonParser.parseString(configJson).asJsonObject
            .getAsJsonObject("route").getAsJsonArray("rule_set")
            .mapNotNull { it.asJsonObject.get("path")?.asString }.toSet()
        paths != setOf(geositePath, geoipPath)
    }.getOrDefault(true)
}

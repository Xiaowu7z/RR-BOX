package com.rr.client.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rr.client.core.model.ProxyNode

object NodeIdentity {
    private val gson = Gson()
    fun key(node: ProxyNode): String {
        val fields = gson.toJsonTree(node).asJsonObject
        listOf("id", "tag", "profileId", "profileName", "nameOverrideOnly").forEach(fields::remove)
        val raw = runCatching { JsonParser.parseString(node.rawJson).asJsonObject }.getOrNull()
        if (raw != null) {
            raw.remove("tag")
            fields.add("rawJson", raw)
        }
        return canonical(fields)
    }
    private fun canonical(value: JsonElement): String = when {
        value.isJsonObject -> value.asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { gson.toJson(it.key) + ":" + canonical(it.value) }
        value.isJsonArray -> value.asJsonArray.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }
}

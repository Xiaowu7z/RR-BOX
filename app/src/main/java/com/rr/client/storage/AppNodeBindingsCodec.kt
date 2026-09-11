package com.rr.client.storage

import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.rr.client.routing.AppNodeBinding
import java.io.StringReader

/** Corrupt explicit routes must stop preparation, never disappear into the main exit. */
object AppNodeBindingsCodec {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun decode(raw: String?): List<AppNodeBinding> {
        if (raw == null) return emptyList()
        try {
            JsonReader(StringReader(raw)).use { reader ->
                reader.strictness = Strictness.STRICT
                require(reader.peek() == JsonToken.BEGIN_ARRAY)
                reader.beginArray()
                val bindings = mutableListOf<AppNodeBinding>()
                while (reader.hasNext()) {
                    require(reader.peek() == JsonToken.BEGIN_OBJECT)
                    reader.beginObject()
                    val seen = mutableSetOf<String>()
                    var app: String? = null
                    var node: String? = null
                    var enabled = true
                    while (reader.hasNext()) {
                        val field = reader.nextName()
                        require(seen.add(field))
                        when (field) {
                            "packageName" -> {
                                require(reader.peek() == JsonToken.STRING)
                                app = reader.nextString()
                            }
                            "nodeId" -> {
                                require(reader.peek() == JsonToken.STRING)
                                node = reader.nextString()
                            }
                            "enabled" -> {
                                require(reader.peek() == JsonToken.BOOLEAN)
                                enabled = reader.nextBoolean()
                            }
                            else -> error("未知应用节点规则字段")
                        }
                    }
                    reader.endObject()
                    bindings += AppNodeBinding(requireNotNull(app), requireNotNull(node), enabled)
                }
                reader.endArray()
                require(reader.peek() == JsonToken.END_DOCUMENT)
                return validate(bindings)
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("应用指定节点配置损坏，请在分流设置中修正规则后再连接", error)
        }
    }

    fun encode(bindings: List<AppNodeBinding>): String = Gson().toJson(validate(bindings))

    fun validate(bindings: List<AppNodeBinding>): List<AppNodeBinding> {
        val seen = mutableSetOf<String>()
        bindings.forEach {
            require(packageName.matches(it.packageName) && it.packageName != "com.rr.client") {
                "应用包名无效"
            }
            require(it.nodeId.isNotBlank() && it.nodeId == it.nodeId.trim()) { "指定节点标识无效" }
            require(seen.add(it.packageName)) { "同一个应用只能指定一个节点" }
        }
        return bindings.sortedBy(AppNodeBinding::packageName)
    }
}

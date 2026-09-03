package com.rr.client.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rr.client.core.model.ProxyNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rr_settings")

class PreferencesManager(private val context: Context) {
    companion object {
        val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        val SMART_ROUTING = booleanPreferencesKey("smart_routing")
        val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode") // "ALL", "ALLOW_LIST", "DISALLOW_LIST"
        val NODE_OVERRIDES_JSON = stringPreferencesKey("node_overrides_json")
    }

    private val gson = Gson()
    private val nodeOverrideType = object : TypeToken<MutableMap<String, ProxyNode>>() {}.type

    val selectedNodeId: Flow<String?> = context.dataStore.data.map { it[SELECTED_NODE_ID] }
    val smartRouting: Flow<Boolean> = context.dataStore.data.map { it[SMART_ROUTING] ?: true }
    val perAppMode: Flow<String> = context.dataStore.data.map { it[PER_APP_PROXY_MODE] ?: "ALL" }
    val nodeOverrides: Flow<Map<String, ProxyNode>> = context.dataStore.data.map { preferences ->
        decodeNodeOverrides(preferences[NODE_OVERRIDES_JSON])
    }

    suspend fun setSelectedNodeId(id: String) {
        context.dataStore.edit { it[SELECTED_NODE_ID] = id }
    }

    suspend fun setSmartRouting(enabled: Boolean) {
        context.dataStore.edit { it[SMART_ROUTING] = enabled }
    }

    suspend fun setPerAppMode(mode: String) {
        context.dataStore.edit { it[PER_APP_PROXY_MODE] = mode }
    }

    suspend fun setNodeOverride(node: ProxyNode) {
        context.dataStore.edit { preferences ->
            val current = decodeNodeOverrides(preferences[NODE_OVERRIDES_JSON]).toMutableMap()
            current[node.id] = node
            preferences[NODE_OVERRIDES_JSON] = gson.toJson(current)
        }
    }

    suspend fun clearNodeOverride(nodeId: String) {
        context.dataStore.edit { preferences ->
            val current = decodeNodeOverrides(preferences[NODE_OVERRIDES_JSON]).toMutableMap()
            current.remove(nodeId)
            if (current.isEmpty()) {
                preferences.remove(NODE_OVERRIDES_JSON)
            } else {
                preferences[NODE_OVERRIDES_JSON] = gson.toJson(current)
            }
        }
    }

    private fun decodeNodeOverrides(rawJson: String?): Map<String, ProxyNode> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<MutableMap<String, ProxyNode>>(rawJson, nodeOverrideType)?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
    }
}

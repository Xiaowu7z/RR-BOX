package com.rr.client.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
        val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode")
        val PER_APP_SELECTED_PACKAGES = stringSetPreferencesKey("per_app_selected_packages")
        val NODE_OVERRIDES_JSON = stringPreferencesKey("node_overrides_json")
        val BACKGROUND_GUIDE_SHOWN = booleanPreferencesKey("background_guide_shown")
        val CHINA_RULESET_LAST_UPDATED = longPreferencesKey("china_ruleset_last_updated")
        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
    }

    private val gson = Gson()
    private val nodeOverrideType = object : TypeToken<MutableMap<String, ProxyNode>>() {}.type

    val selectedNodeId: Flow<String?> = context.dataStore.data.map { it[SELECTED_NODE_ID] }
    val smartRouting: Flow<Boolean> = context.dataStore.data.map { it[SMART_ROUTING] ?: true }
    val perAppMode: Flow<String> = context.dataStore.data.map { it[PER_APP_PROXY_MODE] ?: "ALL" }
    val selectedAppPackages: Flow<Set<String>> = context.dataStore.data.map {
        it[PER_APP_SELECTED_PACKAGES]?.toSet().orEmpty()
    }
    val backgroundGuideShown: Flow<Boolean> = context.dataStore.data.map {
        it[BACKGROUND_GUIDE_SHOWN] ?: false
    }
    val chinaRuleSetLastUpdated: Flow<Long> = context.dataStore.data.map {
        it[CHINA_RULESET_LAST_UPDATED] ?: 0L
    }
    val pinEnabled: Flow<Boolean> = context.dataStore.data.map { it[PIN_ENABLED] ?: false }
    val pinSalt: Flow<String?> = context.dataStore.data.map { it[PIN_SALT] }
    val pinHash: Flow<String?> = context.dataStore.data.map { it[PIN_HASH] }
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

    suspend fun setSelectedAppPackages(packages: Set<String>) {
        val cleaned = packages.filter(String::isNotBlank).toSet()
        context.dataStore.edit { preferences ->
            if (cleaned.isEmpty()) preferences.remove(PER_APP_SELECTED_PACKAGES)
            else preferences[PER_APP_SELECTED_PACKAGES] = cleaned
        }
    }

    /**
     * Atomically toggles one package against the latest DataStore value. This
     * prevents rapid UI taps from overwriting a selection made milliseconds
     * earlier with a stale Compose snapshot.
     */
    suspend fun updateSelectedAppPackage(packageName: String, selected: Boolean): Set<String> {
        val packageValue = packageName.trim()
        require(packageValue.isNotEmpty()) { "应用包名不能为空" }
        var updated: Set<String> = emptySet()
        context.dataStore.edit { preferences ->
            val current = preferences[PER_APP_SELECTED_PACKAGES]?.toMutableSet() ?: mutableSetOf()
            if (selected) current.add(packageValue) else current.remove(packageValue)
            updated = current.toSet()
            if (current.isEmpty()) preferences.remove(PER_APP_SELECTED_PACKAGES)
            else preferences[PER_APP_SELECTED_PACKAGES] = current
        }
        return updated
    }

    suspend fun setBackgroundGuideShown(shown: Boolean) {
        context.dataStore.edit { it[BACKGROUND_GUIDE_SHOWN] = shown }
    }

    suspend fun setChinaRuleSetLastUpdated(timestamp: Long) {
        context.dataStore.edit { it[CHINA_RULESET_LAST_UPDATED] = timestamp.coerceAtLeast(0L) }
    }

    suspend fun savePinCredential(saltBase64: String, hashBase64: String) {
        context.dataStore.edit { preferences ->
            preferences[PIN_SALT] = saltBase64
            preferences[PIN_HASH] = hashBase64
            preferences[PIN_ENABLED] = true
        }
    }

    suspend fun disablePinLock() {
        context.dataStore.edit { preferences ->
            preferences[PIN_ENABLED] = false
            preferences.remove(PIN_SALT)
            preferences.remove(PIN_HASH)
        }
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

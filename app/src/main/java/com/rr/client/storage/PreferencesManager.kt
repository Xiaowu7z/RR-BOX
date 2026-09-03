package com.rr.client.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val FAST_FORWARDING = booleanPreferencesKey("fast_forwarding")
        val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode")

        // Legacy 0.1.6/0.1.7 shared selection. Keep only as a migration seed.
        val PER_APP_SELECTED_PACKAGES = stringSetPreferencesKey("per_app_selected_packages")
        val PROXY_SELECTED_PACKAGES = stringSetPreferencesKey("proxy_selected_packages")
        val BYPASS_SELECTED_PACKAGES = stringSetPreferencesKey("bypass_selected_packages")

        val NODE_OVERRIDES_JSON = stringPreferencesKey("node_overrides_json")
        val BACKGROUND_GUIDE_SHOWN = booleanPreferencesKey("background_guide_shown")
        val CHINA_RULESET_LAST_UPDATED = longPreferencesKey("china_ruleset_last_updated")

        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_MAX_FAILED_ATTEMPTS = intPreferencesKey("pin_max_failed_attempts")
        val PIN_FAILED_ATTEMPTS = intPreferencesKey("pin_failed_attempts")

        const val DEFAULT_PIN_MAX_FAILED_ATTEMPTS = 5
        const val MIN_PIN_MAX_FAILED_ATTEMPTS = 3
        const val MAX_PIN_MAX_FAILED_ATTEMPTS = 50
    }

    private val gson = Gson()
    private val nodeOverrideType = object : TypeToken<MutableMap<String, ProxyNode>>() {}.type

    val selectedNodeId: Flow<String?> = context.dataStore.data.map { it[SELECTED_NODE_ID] }
    val smartRouting: Flow<Boolean> = context.dataStore.data.map { it[SMART_ROUTING] ?: true }
    val fastForwarding: Flow<Boolean> = context.dataStore.data.map { it[FAST_FORWARDING] ?: false }
    val perAppMode: Flow<String> = context.dataStore.data.map { it[PER_APP_PROXY_MODE] ?: "ALL" }

    val proxySelectedAppPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[PROXY_SELECTED_PACKAGES]?.toSet()
            ?: preferences[PER_APP_SELECTED_PACKAGES]?.toSet()
            ?: emptySet()
    }

    val bypassSelectedAppPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[BYPASS_SELECTED_PACKAGES]?.toSet()
            ?: preferences[PER_APP_SELECTED_PACKAGES]?.toSet()
            ?: emptySet()
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
    val pinMaxFailedAttempts: Flow<Int> = context.dataStore.data.map {
        (it[PIN_MAX_FAILED_ATTEMPTS] ?: DEFAULT_PIN_MAX_FAILED_ATTEMPTS)
            .coerceIn(MIN_PIN_MAX_FAILED_ATTEMPTS, MAX_PIN_MAX_FAILED_ATTEMPTS)
    }
    val pinFailedAttempts: Flow<Int> = context.dataStore.data.map {
        (it[PIN_FAILED_ATTEMPTS] ?: 0).coerceAtLeast(0)
    }

    val nodeOverrides: Flow<Map<String, ProxyNode>> = context.dataStore.data.map { preferences ->
        decodeNodeOverrides(preferences[NODE_OVERRIDES_JSON])
    }

    suspend fun setSelectedNodeId(id: String) {
        context.dataStore.edit { it[SELECTED_NODE_ID] = id }
    }

    suspend fun setSmartRouting(enabled: Boolean) {
        context.dataStore.edit { it[SMART_ROUTING] = enabled }
    }

    suspend fun setFastForwarding(enabled: Boolean) {
        context.dataStore.edit { it[FAST_FORWARDING] = enabled }
    }

    suspend fun setPerAppMode(mode: String) {
        context.dataStore.edit { it[PER_APP_PROXY_MODE] = mode }
    }

    suspend fun setProxySelectedAppPackages(packages: Set<String>) {
        setPackageSet(PROXY_SELECTED_PACKAGES, packages)
    }

    suspend fun setBypassSelectedAppPackages(packages: Set<String>) {
        setPackageSet(BYPASS_SELECTED_PACKAGES, packages)
    }

    private suspend fun setPackageSet(key: androidx.datastore.preferences.core.Preferences.Key<Set<String>>, packages: Set<String>) {
        val cleaned = packages.asSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        context.dataStore.edit { preferences ->
            if (cleaned.isEmpty()) preferences.remove(key) else preferences[key] = cleaned
        }
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
            preferences[PIN_FAILED_ATTEMPTS] = 0
            if (preferences[PIN_MAX_FAILED_ATTEMPTS] == null) {
                preferences[PIN_MAX_FAILED_ATTEMPTS] = DEFAULT_PIN_MAX_FAILED_ATTEMPTS
            }
        }
    }

    suspend fun disablePinLock() {
        context.dataStore.edit { preferences ->
            preferences[PIN_ENABLED] = false
            preferences.remove(PIN_SALT)
            preferences.remove(PIN_HASH)
            preferences.remove(PIN_FAILED_ATTEMPTS)
        }
    }

    suspend fun setPinMaxFailedAttempts(value: Int) {
        require(value in MIN_PIN_MAX_FAILED_ATTEMPTS..MAX_PIN_MAX_FAILED_ATTEMPTS) {
            "PIN 错误次数必须在 $MIN_PIN_MAX_FAILED_ATTEMPTS-$MAX_PIN_MAX_FAILED_ATTEMPTS 之间"
        }
        context.dataStore.edit { it[PIN_MAX_FAILED_ATTEMPTS] = value }
    }

    suspend fun recordPinFailure(): Pair<Int, Int> {
        var attempts = 0
        var maxAttempts = DEFAULT_PIN_MAX_FAILED_ATTEMPTS
        context.dataStore.edit { preferences ->
            maxAttempts = (preferences[PIN_MAX_FAILED_ATTEMPTS] ?: DEFAULT_PIN_MAX_FAILED_ATTEMPTS)
                .coerceIn(MIN_PIN_MAX_FAILED_ATTEMPTS, MAX_PIN_MAX_FAILED_ATTEMPTS)
            attempts = (preferences[PIN_FAILED_ATTEMPTS] ?: 0).coerceAtLeast(0) + 1
            preferences[PIN_FAILED_ATTEMPTS] = attempts
        }
        return attempts to maxAttempts
    }

    suspend fun resetPinFailures() {
        context.dataStore.edit { it[PIN_FAILED_ATTEMPTS] = 0 }
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

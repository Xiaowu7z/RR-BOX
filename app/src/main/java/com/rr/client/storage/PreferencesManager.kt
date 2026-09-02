package com.rr.client.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "rr_settings")

class PreferencesManager(private val context: Context) {
    companion object {
        val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        val SMART_ROUTING = booleanPreferencesKey("smart_routing")
        val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode") // "ALL", "ALLOW_LIST", "DISALLOW_LIST"
    }

    val selectedNodeId: Flow<String?> = context.dataStore.data.map { it[SELECTED_NODE_ID] }
    val smartRouting: Flow<Boolean> = context.dataStore.data.map { it[SMART_ROUTING] ?: true }
    val perAppMode: Flow<String> = context.dataStore.data.map { it[PER_APP_PROXY_MODE] ?: "ALL" }

    suspend fun setSelectedNodeId(id: String) {
        context.dataStore.edit { it[SELECTED_NODE_ID] = id }
    }

    suspend fun setSmartRouting(enabled: Boolean) {
        context.dataStore.edit { it[SMART_ROUTING] = enabled }
    }

    suspend fun setPerAppMode(mode: String) {
        context.dataStore.edit { it[PER_APP_PROXY_MODE] = mode }
    }
}

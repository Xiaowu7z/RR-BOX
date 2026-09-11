package com.rr.client.vpn

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rr.client.routing.AppNodeBinding
import com.rr.client.routing.AppNodeRouting
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.storage.AppNodeBindingsCodec
import java.io.File

data class VpnRuntimeState(
    val configJson: String,
    val nodeTag: String,
    val nodeId: String,
    val perAppMode: String = PerAppPolicyResolver.MODE_ALL,
    val selectedPackages: Set<String> = emptySet(),
    /** Null means a legacy cache; Quick Settings rebuilds it before use. */
    val smartRouting: Boolean? = null,
    /** Null means a legacy cache; Quick Settings rebuilds it before use. */
    val fastForwarding: Boolean? = null,
    val savedAtMillis: Long = 0L,
    /** Null marks a legacy cache. Even disabled binding changes invalidate reuse. */
    val appNodeBindings: List<AppNodeBinding>? = emptyList()
)

class VpnRuntimeStateStore(context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "vpn-runtime-state.json")

    fun save(state: VpnRuntimeState) = synchronized(lock) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(gson.toJson(state.copy(savedAtMillis = System.currentTimeMillis())).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }

    fun load(): VpnRuntimeState? = synchronized(lock) {
        runCatching {
            val json = JsonParser.parseString(String(AtomicFile(file).readFully(), Charsets.UTF_8)).asJsonObject
            val bindings = if (json.has("appNodeBindings")) {
                AppNodeBindingsCodec.decode(json.get("appNodeBindings").toString())
            } else null
            gson.fromJson(json, VpnRuntimeState::class.java)
                ?.copy(appNodeBindings = bindings)
                ?.takeIf {
                    it.configJson.isNotBlank() && it.nodeId.isNotBlank() &&
                        AppNodeRouting.requiredNodeIds(it.configJson, it.nodeId).isNotEmpty()
                }
        }.getOrNull()
    }

    fun clear() = synchronized(lock) {
        AtomicFile(file).delete()
        File(file.parentFile, ".${file.name}.tmp").delete()
        Unit
    }

    private companion object { val lock = Any() }
}

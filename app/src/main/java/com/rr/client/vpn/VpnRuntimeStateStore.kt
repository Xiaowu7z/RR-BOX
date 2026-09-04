package com.rr.client.vpn

import android.content.Context
import com.google.gson.Gson
import com.rr.client.routing.PerAppPolicyResolver
import java.io.File

data class VpnRuntimeState(
    val configJson: String,
    val nodeTag: String,
    val nodeId: String,
    val perAppMode: String = PerAppPolicyResolver.MODE_ALL,
    val selectedPackages: Set<String> = emptySet(),
    /** Null means this cache was written by an older build; accept once, then refresh it. */
    val smartRouting: Boolean? = null,
    /** Null means this cache was written by an older build; accept once, then refresh it. */
    val fastForwarding: Boolean? = null,
    val savedAtMillis: Long = 0L
)

class VpnRuntimeStateStore(context: Context) {
    private val gson = Gson()
    private val file = File(context.filesDir, "vpn-runtime-state.json")

    fun save(state: VpnRuntimeState) {
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(gson.toJson(state.copy(savedAtMillis = System.currentTimeMillis())))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun load(): VpnRuntimeState? = runCatching {
        if (!file.isFile || file.length() == 0L) return@runCatching null
        gson.fromJson(file.readText(), VpnRuntimeState::class.java)
            ?.takeIf { it.configJson.isNotBlank() }
    }.getOrNull()

    fun clear() {
        file.delete()
        File(file.parentFile, ".${file.name}.tmp").delete()
    }
}

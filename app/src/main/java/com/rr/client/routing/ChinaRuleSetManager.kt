package com.rr.client.routing

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonParser
import com.rr.client.vpn.VpnRuntimeStateStore
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Bundled offline recovery and bounded, validated, all-or-nothing China rule updates. */
object ChinaRuleSetManager {
    data class Paths(val geositeChina: String, val geoipChina: String)

    data class UpdateResult(
        val updatedAtMillis: Long,
        val totalBytes: Long,
        val generation: String = "",
        val changed: Boolean = true
    )

    private data class RuleSpec(val fileName: String, val urls: List<String>)

    private val specs = listOf(
        RuleSpec("geosite-geolocation-cn.srs", listOf(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-cn.srs",
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-geolocation-cn.srs"
        )),
        RuleSpec("geoip-cn.srs", listOf(
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
            "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"
        ))
    )

    private val stores = mutableMapOf<String, RuleSetGenerationStore>()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }

    fun ensureBundled(context: Context): Result<Paths> = runCatching {
        val store = store(context)
        store.current()?.let { return@runCatching it.paths() }
        // Keep the old flat paths: an already saved runtime may still refer to them.
        val legacy = specs.map { File(File(context.filesDir, "rules"), it.fileName) }
        if (legacy.all { it.isFile }) {
            runCatching {
                store.install(onlyIfMissing = true) { targets ->
                    legacy.zip(targets).forEach { (source, target) ->
                        source.inputStream().use { input ->
                            target.outputStream().use { SrsRuleSetValidation.copyBounded(input, it) }
                        }
                    }
                }
            }.getOrNull()?.let { return@runCatching it.snapshot.paths() }
        }
        store.install(onlyIfMissing = true) { targets ->
            specs.zip(targets).forEach { (spec, target) ->
                context.assets.open("rules/${spec.fileName}").use { input ->
                    target.outputStream().use { SrsRuleSetValidation.copyBounded(input, it) }
                }
            }
        }.snapshot.paths()
    }

    fun currentPaths(context: Context): Paths? = runCatching { store(context).current()?.paths() }.getOrNull()

    suspend fun update(context: Context): Result<UpdateResult> = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        runCatching {
            val result = store(context).install { targets ->
                specs.zip(targets).forEach { (spec, target) ->
                    coroutineContext.ensureActive()
                    downloadFirstAvailable(spec.urls, target) { coroutineContext.ensureActive() }
                }
                coroutineContext.ensureActive()
            }
            UpdateResult(
                result.snapshot.updatedAtMillis,
                result.snapshot.files.sumOf { it.length() },
                result.snapshot.generation,
                result.changed
            )
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun downloadFirstAvailable(urls: List<String>, target: File, ensureActive: () -> Unit) {
        var lastError: Throwable? = null
        for (url in urls) {
            ensureActive()
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "RRBOX rule-set updater").build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    require(response.request.url.isHttps) { "规则更新要求 HTTPS" }
                    val body = response.body ?: error("空响应")
                    require(body.contentLength() <= SrsRuleSetValidation.MAX_FILE_BYTES) { "规则集下载超过 8 MiB 限制" }
                    target.outputStream().use { output ->
                        body.byteStream().use { input -> SrsRuleSetValidation.copyBounded(input, output, ensureActive) }
                    }
                }
                // Try the mirror on corrupt/incompatible bodies, not just HTTP failures.
                SrsRuleSetValidation.validate(listOf(target), Libbox::checkConfig)
                return
            } catch (error: Exception) {
                target.delete()
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw IllegalStateException("规则集下载失败，继续使用原有规则", lastError)
    }

    private fun store(context: Context): RuleSetGenerationStore = synchronized(stores) {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, "rules")
        stores.getOrPut(directory.absolutePath) {
            RuleSetGenerationStore(
                directory = directory,
                fileNames = specs.map { it.fileName },
                validate = { files -> SrsRuleSetValidation.validate(files, Libbox::checkConfig) },
                protectedPaths = {
                    // Continuity recovery intentionally reuses its last validated configuration.
                    val cached = VpnRuntimeStateStore(appContext).load()
                    if (cached == null) emptySet() else {
                        JsonParser.parseString(cached.configJson).asJsonObject
                            .getAsJsonObject("route")?.getAsJsonArray("rule_set")
                            ?.mapNotNull { it.asJsonObject.get("path")?.asString }?.toSet().orEmpty()
                    }
                },
                directorySync = { path ->
                    // Android's Java FileChannel may reject directories with EISDIR.
                    require(path.isDirectory) { "规则集同步目标必须是目录" }
                    val closeOnExec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        OsConstants.O_CLOEXEC
                    } else 0
                    val descriptor = Os.open(path.absolutePath, OsConstants.O_RDONLY or closeOnExec, 0)
                    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
                }
            )
        }
    }

    private fun RuleSetGenerationStore.Snapshot.paths() = Paths(files[0].absolutePath, files[1].absolutePath)
}

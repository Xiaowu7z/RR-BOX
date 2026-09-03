package com.rr.client.routing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Maintains the two binary sing-box rule-sets RRBOX needs for mainland routing:
 * China domains and China IP ranges. Build-time snapshots are bundled in the APK;
 * runtime updates replace both files only after both pass validation.
 */
object ChinaRuleSetManager {
    // sing-box v1.14.0 constant.RuleSetVersionCurrent == 5.
    private const val MAX_SUPPORTED_SRS_VERSION = 5

    data class Paths(
        val geositeChina: String,
        val geoipChina: String
    )

    data class UpdateResult(
        val updatedAtMillis: Long,
        val totalBytes: Long
    )

    private data class RuleSpec(
        val assetName: String,
        val localName: String,
        val urls: List<String>
    )

    private val specs = listOf(
        RuleSpec(
            assetName = "geosite-geolocation-cn.srs",
            localName = "geosite-geolocation-cn.srs",
            urls = listOf(
                "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-geolocation-cn.srs",
                "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-geolocation-cn.srs"
            )
        ),
        RuleSpec(
            assetName = "geoip-cn.srs",
            localName = "geoip-cn.srs",
            urls = listOf(
                "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
                "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"
            )
        )
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun ensureBundled(context: Context): Result<Paths> = runCatching {
        val directory = ruleDirectory(context).apply { mkdirs() }
        specs.forEach { spec ->
            val destination = File(directory, spec.localName)
            if (!isValidSrs(destination)) {
                val temporary = File(directory, ".${spec.localName}.asset.tmp")
                temporary.delete()
                context.assets.open("rules/${spec.assetName}").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                require(isValidSrs(temporary)) {
                    "内置规则集损坏或版本不兼容：${spec.localName}"
                }
                replaceAtomically(temporary, destination)
            }
        }
        currentPaths(context) ?: error("中国规则集初始化失败")
    }

    fun currentPaths(context: Context): Paths? {
        val directory = ruleDirectory(context)
        val geositeChina = File(directory, "geosite-geolocation-cn.srs")
        val geoipChina = File(directory, "geoip-cn.srs")
        if (!listOf(geositeChina, geoipChina).all(::isValidSrs)) return null
        return Paths(
            geositeChina = geositeChina.absolutePath,
            geoipChina = geoipChina.absolutePath
        )
    }

    suspend fun update(context: Context): Result<UpdateResult> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = ruleDirectory(context).apply { mkdirs() }
            val downloaded = mutableListOf<Pair<File, File>>()
            try {
                specs.forEach { spec ->
                    val temp = File(directory, ".${spec.localName}.download.tmp")
                    temp.delete()
                    downloadFirstAvailable(spec.urls, temp)
                    require(isValidSrs(temp)) {
                        "下载到的规则集无效或高于 sing-box 1.14 支持版本：${spec.localName}"
                    }
                    downloaded += temp to File(directory, spec.localName)
                }

                // Commit only after both files passed validation.
                downloaded.forEach { (temp, destination) -> replaceAtomically(temp, destination) }
                val paths = currentPaths(context) ?: error("规则集更新后校验失败")
                val bytes = listOf(paths.geositeChina, paths.geoipChina)
                    .sumOf { File(it).length() }
                UpdateResult(System.currentTimeMillis(), bytes)
            } finally {
                directory.listFiles { file ->
                    file.name.endsWith(".download.tmp") || file.name.endsWith(".asset.tmp")
                }?.forEach(File::delete)
            }
        }
    }

    private fun downloadFirstAvailable(urls: List<String>, target: File) {
        var lastError: Throwable? = null
        for (url in urls) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "RRBOX rule-set updater")
                    .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("空响应")
                    target.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
            }
            if (result.isSuccess && isValidSrs(target)) return
            lastError = result.exceptionOrNull()
            target.delete()
        }
        throw IllegalStateException("规则集下载失败", lastError)
    }

    internal fun isValidSrs(file: File): Boolean {
        if (!file.isFile || file.length() < 8L) return false
        return runCatching {
            file.inputStream().use { input ->
                val s = input.read()
                val r = input.read()
                val s2 = input.read()
                val version = input.read()
                s == 0x53 && r == 0x52 && s2 == 0x53 &&
                    version in 1..MAX_SUPPORTED_SRS_VERSION
            }
        }.getOrDefault(false)
    }

    private fun replaceAtomically(source: File, destination: File) {
        val backup = File(destination.parentFile, ".${destination.name}.bak")
        backup.delete()
        if (destination.exists() && !destination.renameTo(backup)) {
            destination.copyTo(backup, overwrite = true)
            destination.delete()
        }
        try {
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
            require(isValidSrs(destination)) { "规则集写入失败：${destination.name}" }
            backup.delete()
        } catch (error: Throwable) {
            destination.delete()
            if (backup.exists()) backup.renameTo(destination)
            throw error
        }
    }

    private fun ruleDirectory(context: Context) = File(context.filesDir, "rules")
}

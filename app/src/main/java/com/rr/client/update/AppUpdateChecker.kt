package com.rr.client.update

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AppUpdateResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val downloadUrl: String,
    val updateAvailable: Boolean
)

object AppUpdateChecker {
    private const val OWNER = "Xiaowu7z"
    private const val REPOSITORY = "RR-BOX"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun check(currentVersion: String): Result<AppUpdateResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "RRBOX update checker")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    error("暂未找到公开的 RRBOX 正式版 Release")
                }
                require(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                require(body.isNotBlank()) { "GitHub Release 响应为空" }

                val root = JsonParser.parseString(body).asJsonObject
                val tag = root.get("tag_name")?.asString.orEmpty()
                val name = root.get("name")?.asString.orEmpty().ifBlank { tag }
                val releaseUrl = root.get("html_url")?.asString.orEmpty()
                require(tag.isNotBlank() && releaseUrl.isNotBlank()) { "Release 信息不完整" }

                val apkUrl = root.getAsJsonArray("assets")
                    ?.mapNotNull { element ->
                        val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val assetName = asset.get("name")?.asString.orEmpty()
                        val url = asset.get("browser_download_url")?.asString.orEmpty()
                        if (assetName.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                            assetName to url
                        } else null
                    }
                    ?.sortedByDescending { (assetName, _) ->
                        when {
                            assetName.contains("arm64", ignoreCase = true) -> 2
                            assetName.contains("RRBOX", ignoreCase = true) -> 1
                            else -> 0
                        }
                    }
                    ?.firstOrNull()
                    ?.second

                AppUpdateResult(
                    currentVersion = currentVersion,
                    latestVersion = tag,
                    releaseName = name,
                    downloadUrl = apkUrl ?: releaseUrl,
                    updateAvailable = compareVersions(tag, currentVersion) > 0
                )
            }
        }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = numericVersion(left)
        val b = numericVersion(right)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun numericVersion(raw: String): List<Int> {
        val match = Regex("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").find(raw)
            ?: return emptyList()
        return listOf(
            match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0,
            match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0,
            match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        )
    }
}

package com.rr.client.subscription

import com.google.gson.JsonParser
import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.model.SubscriptionUserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SubscriptionFetchResult(
    val nodes: List<ProxyNode>,
    val userInfo: SubscriptionUserInfo,
    /** Complete client profile when the response contains a TUN inbound. */
    val fullSingBoxProfile: String?
)

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun fetchSubscription(url: String): Result<SubscriptionFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = url.trim()
            require(normalizedUrl.isNotEmpty()) { "订阅地址不能为空" }

            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "RR-Client/0.1.1 (Android; sing-box/1.14.0)")
                .header("Accept", "application/json,text/plain,*/*")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("订阅请求失败：HTTP ${response.code}")
                }

                val body = response.body?.string().orEmpty().trim()
                require(body.isNotEmpty()) { "订阅响应为空" }

                val userInfo = SubscriptionParser.parseUserInfoHeader(
                    response.header("Subscription-Userinfo")
                )
                val nodes = SubscriptionParser.parseContent(body)
                require(nodes.isNotEmpty()) { "订阅中没有识别到可用代理节点" }

                SubscriptionFetchResult(
                    nodes = nodes,
                    userInfo = userInfo,
                    fullSingBoxProfile = body.takeIf(::isCompleteSingBoxProfile)
                )
            }
        }
    }

    private fun isCompleteSingBoxProfile(content: String): Boolean {
        if (!content.startsWith("{")) return false
        return runCatching {
            val root = JsonParser.parseString(content).asJsonObject
            val hasOutbounds = root.getAsJsonArray("outbounds")?.size()?.let { it > 0 } == true
            val hasTun = root.getAsJsonArray("inbounds")
                ?.any { element ->
                    element.isJsonObject && element.asJsonObject.get("type")?.asString == "tun"
                } == true
            hasOutbounds && hasTun
        }.getOrDefault(false)
    }
}

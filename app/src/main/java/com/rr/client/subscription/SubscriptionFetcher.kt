package com.rr.client.subscription

import com.rr.client.core.model.ProxyNode
import com.rr.client.subscription.model.SubscriptionUserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SubscriptionFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun fetchSubscription(
        url: String
    ): Result<Pair<List<ProxyNode>, SubscriptionUserInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = url.trim()
            require(normalizedUrl.startsWith("https://") || normalizedUrl.startsWith("http://")) {
                "订阅地址必须以 http:// 或 https:// 开头"
            }

            val request = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", "RR-Client/0.1.1-alpha (Android; sing-box/1.14.0)")
                .header("Accept", "application/json,text/plain,*/*")
                .build()

            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "订阅服务器返回 HTTP ${response.code}" }

                val body = response.body?.string().orEmpty()
                check(body.isNotBlank()) { "订阅内容为空" }

                val userInfo = SubscriptionParser.parseUserInfoHeader(
                    response.header("Subscription-Userinfo")
                )
                val nodes = SubscriptionParser.parseContent(body)
                check(nodes.isNotEmpty()) { "订阅中没有可连接的代理节点" }

                nodes to userInfo
            }
        }
    }
}

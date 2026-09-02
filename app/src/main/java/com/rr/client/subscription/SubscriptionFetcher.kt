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
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchSubscription(url: String): Result<Pair<List<ProxyNode>, SubscriptionUserInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "RR-Client/0.1.0 (Android; sing-box/1.14.0)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val userInfoHeader = response.header("Subscription-Userinfo")
            val userInfo = SubscriptionParser.parseUserInfoHeader(userInfoHeader)
            val nodes = SubscriptionParser.parseContent(body)

            if (nodes.isEmpty()) {
                return@withContext Result.failure(Exception("Subscription returned 0 valid proxy nodes."))
            }

            Result.success(Pair(nodes, userInfo))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

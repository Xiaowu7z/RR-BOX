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
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
) {
    suspend fun fetchSubscription(
        url: String,
        profileId: String,
        profileName: String
    ): Result<Pair<List<ProxyNode>, SubscriptionUserInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = SubscriptionUrlNormalizer.candidates(url)
            val failures = mutableListOf<String>()

            for (candidate in candidates) {
                for (userAgent in COMPATIBILITY_USER_AGENTS) {
                    val attempt = runCatching {
                        val request = Request.Builder()
                            .url(candidate)
                            .header("User-Agent", userAgent)
                            .header("Accept", "*/*")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                error("HTTP ${response.code}")
                            }
                            val body = response.body?.string().orEmpty()
                            if (body.isBlank()) error("订阅返回空内容")

                            val nodes = SubscriptionParser.parseContent(body, profileId, profileName)
                            if (nodes.isEmpty()) error("返回内容中没有识别到可用节点")

                            val userInfo = SubscriptionParser.parseUserInfoHeader(
                                response.header("Subscription-Userinfo")
                                    ?: response.header("subscription-userinfo")
                            )
                            Pair(nodes, userInfo)
                        }
                    }

                    attempt.getOrNull()?.let { return@runCatching it }
                    val message = attempt.exceptionOrNull()?.message ?: "未知错误"
                    failures += "${candidate.substringBefore('?')} [$userAgent]: $message"
                }
            }

            val concise = failures.distinct().takeLast(4).joinToString("；")
            error("无法读取该订阅。已尝试 HTTPS/HTTP 与主流客户端格式${if (concise.isBlank()) "" else "：$concise"}")
        }
    }

    companion object {
        /**
         * Subscription panels often return different formats according to User-Agent.
         * Prefer our own UA, then request formats widely supported by modern panels.
         */
        private val COMPATIBILITY_USER_AGENTS = listOf(
            "RRBOX/0.2 (Android; sing-box/1.14.0)",
            "sing-box",
            "NekoBox",
            "v2rayN/7.0",
            "clash.meta"
        )
    }
}

package com.rr.client.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.rr.client.storage.PreferencesManager
import com.rr.client.vpn.HevTunnelNative
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Active probes used only by Network Lab A/B v2.7.
 *
 * The proven v2.5/v2.6 traffic topology is unchanged. v2.7 only changes accounting semantics:
 * sessionTraffic and HEV native RX are path validators, not byte-perfect payload meters. After the
 * HTTPS body completes we wait at least one status interval, then accept the round once both path
 * counters cover >=80% of payload. The wait is bounded so unrelated background traffic cannot keep
 * a round open for 4-6 seconds merely because the global counters keep moving.
 */
internal object EngineBenchmarkProbe {
    const val HTTPS_HOST = "speed.cloudflare.com"
    const val HTTPS_ATTEMPTS = 3
    const val PREFLIGHT_BYTES = 64L * 1024L
    const val DOWNLOAD_BYTES = 2L * 1024L * 1024L
    const val PROBE_TRANSPORT =
        "RRBOX UID natural VPN routing + fixed IPv4 bootstrap + bounded accounting"

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val CALL_TIMEOUT_SECONDS = 25L

    data class ProbeTarget(
        val address: InetAddress,
        val source: String
    ) {
        val addressText: String
            get() = address.hostAddress ?: address.toString()

        val label: String
            get() = "$addressText via $source"
    }

    suspend fun resolveTarget(context: Context): ProbeTarget = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

        if (connectivity != null) {
            val candidates = connectivity.allNetworks.mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                val link = connectivity.getLinkProperties(network)
                val interfaceName = link?.interfaceName.orEmpty().ifBlank { "physical" }
                val score =
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 4 else 0) +
                        (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 0) +
                        (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) 1 else 0)
                Triple(network, interfaceName, score)
            }.sortedByDescending { it.third }

            for ((network, interfaceName, _) in candidates) {
                val ipv4 = runCatching {
                    network.getAllByName(HTTPS_HOST)
                        .filterIsInstance<Inet4Address>()
                        .distinctBy { it.hostAddress }
                        .firstOrNull()
                }.getOrNull()
                if (ipv4 != null) {
                    return@withContext ProbeTarget(ipv4, "physical:$interfaceName")
                }
            }
        }

        val fallback = runCatching {
            InetAddress.getAllByName(HTTPS_HOST)
                .filterIsInstance<Inet4Address>()
                .distinctBy { it.hostAddress }
                .firstOrNull()
        }.getOrNull()
            ?: throw UnknownHostException("启动前固定解析失败：$HTTPS_HOST 未解析到 IPv4")

        ProbeTarget(fallback, "system-bootstrap")
    }

    suspend fun httpsRound(
        context: Context,
        engine: String,
        target: ProbeTarget,
        attempt: Int,
        downloadBytes: Long = DOWNLOAD_BYTES
    ): HttpsProbeRound = withContext(Dispatchers.IO) {
        require(downloadBytes > 0L) { "HTTPS 测试字节必须大于 0" }

        val vpnLabel = observeVpnNetwork(context.applicationContext)
        val proxyStart = RRVpnService.sessionTraffic.value.proxyDownloadTotal
        val requireHevNative = engine == PreferencesManager.TUN_ENGINE_HEV
        val nativeStartRx = if (requireHevNative) nativeRxBytes() else null
        if (requireHevNative && nativeStartRx == null) {
            return@withContext HttpsProbeRound(
                attempt = attempt,
                success = false,
                protocol = "$vpnLabel/fixed=${target.addressText}",
                error = "HEV native 统计不可用"
            )
        }

        val events = ProbeEventListener()
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(HTTPS_HOST, ignoreCase = true)) {
                    throw UnknownHostException("v2.7 不允许测速重定向到其他主机: $hostname")
                }
                return listOf(target.address)
            }
        }
        val client = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .eventListener(events)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .dns(pinnedDns)
            .build()

        val nonce = "${SystemClock.elapsedRealtimeNanos()}-$engine-$attempt-$downloadBytes"
        val request = Request.Builder()
            .url("https://$HTTPS_HOST/__down?bytes=$downloadBytes&rrbox=$nonce")
            .header("User-Agent", "RRBOX-Network-Lab/2.7")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
            .build()

        val callStartedNs = System.nanoTime()
        var httpCode: Int? = null
        var protocol: String? = "$vpnLabel/fixed=${target.addressText}"

        try {
            var bytesReceived = 0L
            var firstByteNs: Long? = null
            var finishedNs = callStartedNs

            client.newCall(request).execute().use { response ->
                httpCode = response.code
                protocol = "$vpnLabel/fixed=${target.addressText}/${response.protocol}"
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

                val body = response.body ?: throw IOException("HTTPS 响应正文为空")
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (firstByteNs == null) firstByteNs = System.nanoTime()
                        bytesReceived += read.toLong()
                    }
                    finishedNs = System.nanoTime()
                }
            }

            if (bytesReceived < downloadBytes) {
                throw IOException("HTTPS 响应正文不足: $bytesReceived/$downloadBytes bytes")
            }

            val firstNs = firstByteNs ?: finishedNs
            val transferNs = (finishedNs - firstNs).coerceAtLeast(1L)
            val downloadBps = (bytesReceived.toDouble() * 1_000_000_000.0 / transferNs.toDouble())
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .roundToLong()
            val accounting = awaitPathAccounting(
                proxyStart = proxyStart,
                nativeStartRx = nativeStartRx,
                expectedBytes = downloadBytes,
                requireHevNative = requireHevNative
            )

            HttpsProbeRound(
                attempt = attempt,
                success = true,
                dnsMillis = null,
                tcpConnectMillis = events.tcpConnectMillis,
                tlsMillis = events.tlsMillis,
                firstByteMillis = nanosToMillis(firstNs - callStartedNs),
                downloadMillis = nanosToMillis(finishedNs - firstNs),
                bytesReceived = bytesReceived,
                downloadBps = downloadBps,
                proxyAccountedDownloadBytes = accounting.proxyBytes,
                nativeAccountedDownloadBytes = accounting.nativeRxBytes,
                nativePathVerified = accounting.nativeVerified,
                proxyPathVerified = accounting.pathVerified,
                accountingSettleMillis = accounting.waitMillis,
                httpCode = httpCode,
                protocol = protocol
            )
        } catch (error: Throwable) {
            HttpsProbeRound(
                attempt = attempt,
                success = false,
                dnsMillis = null,
                tcpConnectMillis = events.tcpConnectMillis,
                tlsMillis = events.tlsMillis,
                httpCode = httpCode,
                protocol = protocol,
                error = safeError(error)
            )
        } finally {
            client.connectionPool.evictAll()
            runCatching { client.dispatcher.executorService.shutdown() }
        }
    }

    private fun observeVpnNetwork(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return "VPN-UID[unknown]"
        val selected = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = connectivity.getLinkProperties(network)
            val iface = link?.interfaceName.orEmpty().ifBlank { "vpn" }
            val score =
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 2 else 0) +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0)
            Triple(network, iface, score)
        }.maxByOrNull { it.third } ?: return "VPN-UID[unknown]"
        return "VPN-UID[${selected.second}#${selected.first.networkHandle}]"
    }

    private suspend fun awaitPathAccounting(
        proxyStart: Long,
        nativeStartRx: Long?,
        expectedBytes: Long,
        requireHevNative: Boolean
    ): PathAccounting {
        val threshold = max(
            16L * 1024L,
            expectedBytes * AccountingPolicy.REQUIRED_PERCENT / 100L
        )
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + AccountingPolicy.MAX_WAIT_MILLIS
        var proxyDelta = 0L
        var nativeDelta = 0L

        while (true) {
            val now = SystemClock.elapsedRealtime()
            proxyDelta = (
                RRVpnService.sessionTraffic.value.proxyDownloadTotal - proxyStart
                ).coerceAtLeast(0L)
            if (requireHevNative && nativeStartRx != null) {
                nativeDelta = ((nativeRxBytes() ?: nativeStartRx) - nativeStartRx).coerceAtLeast(0L)
            }

            val proxyOk = proxyDelta >= threshold
            val nativeOk = !requireHevNative || nativeDelta >= threshold
            val minimumWindowElapsed = now - startedAt >= AccountingPolicy.MIN_WAIT_MILLIS
            if (minimumWindowElapsed && proxyOk && nativeOk) break
            if (now >= deadline) break
            delay(AccountingPolicy.POLL_MILLIS)
        }

        val proxyOk = proxyDelta >= threshold
        val nativeOk = !requireHevNative || nativeDelta >= threshold
        return PathAccounting(
            proxyBytes = proxyDelta,
            nativeRxBytes = nativeDelta,
            nativeVerified = nativeOk,
            pathVerified = proxyOk && nativeOk,
            waitMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        )
    }

    private fun nativeRxBytes(): Long? =
        HevTunnelNative.stats()?.getOrNull(3)?.coerceAtLeast(0L)

    private fun nanosToMillis(nanos: Long): Long =
        (nanos.coerceAtLeast(0L).toDouble() / 1_000_000.0)
            .coerceAtLeast(0.1)
            .roundToLong()
            .coerceAtLeast(1L)

    private fun safeError(error: Throwable): String =
        (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName).take(220)

    private data class PathAccounting(
        val proxyBytes: Long,
        val nativeRxBytes: Long,
        val nativeVerified: Boolean,
        val pathVerified: Boolean,
        val waitMillis: Long
    )

    private class ProbeEventListener : EventListener() {
        private var connectStartedNs: Long? = null
        private var tlsStartedNs: Long? = null

        var tcpConnectMillis: Long? = null
            private set
        var tlsMillis: Long? = null
            private set

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartedNs = System.nanoTime()
        }

        override fun secureConnectStart(call: Call) {
            val now = System.nanoTime()
            tcpConnectMillis = durationMillis(connectStartedNs, now)
            tlsStartedNs = now
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMillis = durationMillis(tlsStartedNs, System.nanoTime())
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?
        ) {
            if (tcpConnectMillis == null) {
                tcpConnectMillis = durationMillis(connectStartedNs, System.nanoTime())
            }
        }

        private fun durationMillis(startNs: Long?, endNs: Long): Long? {
            val start = startNs ?: return null
            return nanosToMillis(endNs - start)
        }
    }
}

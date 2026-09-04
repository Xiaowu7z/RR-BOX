package com.rr.client.lab

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Active probes used only by Network Lab A/B v2.3.
 *
 * v2.3 keeps the already verified System/HEV data planes completely unchanged. RRBOX still stays
 * outside the HEV VPN during normal HEV operation. Only the benchmark socket is explicitly created
 * from Android's current VPN Network.socketFactory, with DNS resolved by that same Network.
 *
 * Path validity is checked against sing-box sessionTraffic. HEV adds a second independent check
 * against hev-socks5-tunnel's native TUN RX byte counter before any measurement is accepted.
 */
internal object EngineBenchmarkProbe {
    const val HTTPS_HOST = "speed.cloudflare.com"
    const val HTTPS_ATTEMPTS = 3
    const val PREFLIGHT_BYTES = 64L * 1024L
    const val DOWNLOAD_BYTES = 2L * 1024L * 1024L
    const val PROBE_TRANSPORT = "Android VPN Network.socketFactory"

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val CALL_TIMEOUT_SECONDS = 25L
    private const val ACCOUNTING_WAIT_MILLIS = 3_500L

    suspend fun httpsRound(
        context: Context,
        engine: String,
        attempt: Int,
        downloadBytes: Long = DOWNLOAD_BYTES
    ): HttpsProbeRound = withContext(Dispatchers.IO) {
        require(downloadBytes > 0L) { "HTTPS 测试字节必须大于 0" }

        val binding = runCatching { findVpnNetwork(context.applicationContext) }
            .getOrElse { error ->
                return@withContext HttpsProbeRound(
                    attempt = attempt,
                    success = false,
                    protocol = "VPN-NETWORK",
                    error = safeError(error)
                )
            }

        val proxyStart = RRVpnService.sessionTraffic.value.proxyDownloadTotal
        val requireHevNative = engine == PreferencesManager.TUN_ENGINE_HEV
        val nativeStartRx = if (requireHevNative) nativeRxBytes() else null
        if (requireHevNative && nativeStartRx == null) {
            return@withContext HttpsProbeRound(
                attempt = attempt,
                success = false,
                protocol = binding.label,
                error = "HEV native 统计不可用"
            )
        }

        val events = ProbeEventListener()
        val client = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .eventListener(events)
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .proxy(Proxy.NO_PROXY)
            .socketFactory(binding.network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    binding.network.getAllByName(hostname).toList()
            })
            .build()

        val nonce = "${SystemClock.elapsedRealtimeNanos()}-$engine-$attempt-$downloadBytes"
        val request = Request.Builder()
            .url("https://$HTTPS_HOST/__down?bytes=$downloadBytes&rrbox=$nonce")
            .header("User-Agent", "RRBOX-Network-Lab/2.3")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
            .build()

        val callStartedNs = System.nanoTime()
        var httpCode: Int? = null
        var protocol: String? = binding.label

        try {
            var bytesReceived = 0L
            var firstByteNs: Long? = null
            var finishedNs = callStartedNs

            client.newCall(request).execute().use { response ->
                httpCode = response.code
                protocol = "${binding.label}/${response.protocol}"
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
                dnsMillis = events.dnsMillis,
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
                httpCode = httpCode,
                protocol = protocol
            )
        } catch (error: Throwable) {
            HttpsProbeRound(
                attempt = attempt,
                success = false,
                dnsMillis = events.dnsMillis,
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

    private fun findVpnNetwork(context: Context): VpnNetworkBinding {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: throw IOException("ConnectivityManager 不可用")

        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = connectivity.getLinkProperties(network)
            val interfaceName = link?.interfaceName.orEmpty()
            val score =
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 4 else 0) +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (interfaceName.startsWith("tun") || interfaceName.startsWith("ppp")) 1 else 0)
            Triple(network, interfaceName, score)
        }

        val selected = candidates.maxByOrNull { it.third }
            ?: throw IOException("未找到 Android VPN Network；请确认 RRBOX VPN 已连接")
        val network = selected.first
        val interfaceName = selected.second.ifBlank { "vpn" }
        return VpnNetworkBinding(
            network = network,
            interfaceName = interfaceName,
            handle = network.networkHandle
        )
    }

    private suspend fun awaitPathAccounting(
        proxyStart: Long,
        nativeStartRx: Long?,
        expectedBytes: Long,
        requireHevNative: Boolean
    ): PathAccounting {
        val threshold = max(16L * 1024L, expectedBytes / 2L)
        val deadline = SystemClock.elapsedRealtime() + ACCOUNTING_WAIT_MILLIS
        var proxyDelta = 0L
        var nativeDelta = 0L

        while (SystemClock.elapsedRealtime() < deadline) {
            proxyDelta = (
                RRVpnService.sessionTraffic.value.proxyDownloadTotal - proxyStart
                ).coerceAtLeast(0L)
            if (requireHevNative && nativeStartRx != null) {
                nativeDelta = ((nativeRxBytes() ?: nativeStartRx) - nativeStartRx).coerceAtLeast(0L)
            }
            val proxyOk = proxyDelta >= threshold
            val nativeOk = !requireHevNative || nativeDelta >= threshold
            if (proxyOk && nativeOk) break
            delay(200L)
        }

        val proxyOk = proxyDelta >= threshold
        val nativeOk = !requireHevNative || nativeDelta >= threshold
        return PathAccounting(
            proxyBytes = proxyDelta,
            nativeRxBytes = nativeDelta,
            nativeVerified = nativeOk,
            pathVerified = proxyOk && nativeOk
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

    private data class VpnNetworkBinding(
        val network: Network,
        val interfaceName: String,
        val handle: Long
    ) {
        val label: String
            get() = "VPN-NETWORK[$interfaceName#$handle]"
    }

    private data class PathAccounting(
        val proxyBytes: Long,
        val nativeRxBytes: Long,
        val nativeVerified: Boolean,
        val pathVerified: Boolean
    )

    private class ProbeEventListener : EventListener() {
        private var dnsStartedNs: Long? = null
        private var connectStartedNs: Long? = null
        private var tlsStartedNs: Long? = null

        var dnsMillis: Long? = null
            private set
        var tcpConnectMillis: Long? = null
            private set
        var tlsMillis: Long? = null
            private set

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartedNs = System.nanoTime()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMillis = durationMillis(dnsStartedNs, System.nanoTime())
        }

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

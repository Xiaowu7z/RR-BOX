package com.rr.client.lab

import android.os.SystemClock
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Active probes used only by Network Lab.
 *
 * System mode already keeps RRBOX's own UID inside the VPN. Normal HEV mode deliberately keeps
 * RRBOX outside to avoid a local bridge loop, so A/B v2.1 uses a transient benchmark-only HEV
 * restart that includes RRBOX while excluding 127/8 from the VPN. Core outbound sockets are still
 * released from the VPN by VpnService.protect(fd). This helper never mutates either data plane.
 */
internal object EngineBenchmarkProbe {
    const val HTTPS_HOST = "speed.cloudflare.com"
    const val UDP_HOST = "stun.l.google.com"
    const val UDP_PORT = 19302
    const val HTTPS_ATTEMPTS = 3
    const val UDP_ATTEMPTS = 3
    const val PREFLIGHT_BYTES = 64L * 1024L
    const val DOWNLOAD_BYTES = 2L * 1024L * 1024L

    private const val CONNECT_TIMEOUT_SECONDS = 8L
    private const val READ_TIMEOUT_SECONDS = 12L
    private const val CALL_TIMEOUT_SECONDS = 20L
    private const val UDP_TIMEOUT_MILLIS = 3_000
    private const val ACCOUNTING_WAIT_MILLIS = 2_500L
    private val secureRandom = SecureRandom()

    suspend fun httpsRound(
        attempt: Int,
        downloadBytes: Long = DOWNLOAD_BYTES
    ): HttpsProbeRound = withContext(Dispatchers.IO) {
        require(downloadBytes > 0L) { "HTTPS 测试字节必须大于 0" }

        val accountingStart = RRVpnService.sessionTraffic.value.proxyDownloadTotal
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
            .build()

        val nonce = "${SystemClock.elapsedRealtimeNanos()}-$attempt-$downloadBytes"
        val request = Request.Builder()
            .url("https://$HTTPS_HOST/__down?bytes=$downloadBytes&rrbox=$nonce")
            .header("User-Agent", "RRBOX-Network-Lab/2.1")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-cache")
            .header("Connection", "close")
            .build()

        val callStartedNs = System.nanoTime()
        var httpCode: Int? = null
        var protocol: String? = null

        try {
            var bytesReceived = 0L
            var firstByteNs: Long? = null
            var finishedNs = callStartedNs

            client.newCall(request).execute().use { response ->
                httpCode = response.code
                protocol = response.protocol.toString()
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

            if (bytesReceived <= 0L) throw IOException("HTTPS 未收到响应正文")
            if (bytesReceived < downloadBytes) {
                throw IOException("HTTPS 响应正文不足: $bytesReceived/$downloadBytes bytes")
            }

            val firstNs = firstByteNs ?: finishedNs
            val transferNs = (finishedNs - firstNs).coerceAtLeast(1L)
            val downloadBps = (bytesReceived.toDouble() * 1_000_000_000.0 / transferNs.toDouble())
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .roundToLong()
            val accounting = awaitProxyAccounting(accountingStart, downloadBytes)

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
                proxyAccountedDownloadBytes = accounting.first,
                proxyPathVerified = accounting.second,
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

    suspend fun udpRound(attempt: Int): UdpProbeRound = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getAllByName(UDP_HOST)
                .firstOrNull { it is Inet4Address }
                ?: throw IOException("STUN 未解析到 IPv4")
            val transactionId = ByteArray(12).also(secureRandom::nextBytes)
            val requestBytes = ByteBuffer.allocate(20)
                .putShort(0x0001.toShort())
                .putShort(0x0000.toShort())
                .putInt(0x2112A442)
                .put(transactionId)
                .array()

            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_TIMEOUT_MILLIS
                socket.connect(InetSocketAddress(address, UDP_PORT))
                val startedNs = System.nanoTime()
                socket.send(DatagramPacket(requestBytes, requestBytes.size))

                val responseBytes = ByteArray(512)
                val response = DatagramPacket(responseBytes, responseBytes.size)
                socket.receive(response)
                val elapsedNs = System.nanoTime() - startedNs
                validateStunResponse(responseBytes, response.length, transactionId)

                UdpProbeRound(
                    attempt = attempt,
                    success = true,
                    rttMillis = nanosToMillis(elapsedNs),
                    address = address.hostAddress
                )
            }
        } catch (_: SocketTimeoutException) {
            UdpProbeRound(attempt = attempt, success = false, error = "UDP STUN 超时")
        } catch (error: Throwable) {
            UdpProbeRound(attempt = attempt, success = false, error = safeError(error))
        }
    }

    private suspend fun awaitProxyAccounting(startDown: Long, expectedBytes: Long): Pair<Long, Boolean> {
        // The status channel publishes roughly once per second. Use a conservative payload ratio
        // instead of requiring an exact byte match, while keeping the preflight threshold large
        // enough that ordinary background traffic is very unlikely to create a false PASS.
        val threshold = max(16L * 1024L, expectedBytes / 2L)
        val deadline = SystemClock.elapsedRealtime() + ACCOUNTING_WAIT_MILLIS
        var delta = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            delta = (RRVpnService.sessionTraffic.value.proxyDownloadTotal - startDown).coerceAtLeast(0L)
            if (delta >= threshold) break
            delay(250L)
        }
        return delta to (delta >= threshold)
    }

    private fun validateStunResponse(data: ByteArray, length: Int, expectedTransactionId: ByteArray) {
        if (length < 20) throw IOException("STUN 响应过短")
        val buffer = ByteBuffer.wrap(data, 0, length)
        val type = buffer.short.toInt() and 0xffff
        buffer.short // payload length
        val cookie = buffer.int
        val transactionId = ByteArray(12)
        buffer.get(transactionId)
        if (type != 0x0101) throw IOException("STUN 非成功响应: 0x${type.toString(16)}")
        if (cookie != 0x2112A442) throw IOException("STUN magic cookie 不匹配")
        if (!transactionId.contentEquals(expectedTransactionId)) throw IOException("STUN transaction id 不匹配")
    }

    private fun nanosToMillis(nanos: Long): Long =
        (nanos.coerceAtLeast(0L).toDouble() / 1_000_000.0).coerceAtLeast(0.1).roundToLong().coerceAtLeast(1L)

    private fun safeError(error: Throwable): String =
        (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName).take(160)

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

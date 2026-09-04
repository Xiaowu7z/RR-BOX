package com.rr.client.lab

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import com.rr.client.storage.PreferencesManager
import com.rr.client.vpn.HevTunnelNative
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Active HTTP probes used only by Network Lab A/B v2.2.
 *
 * The actual download is owned by Android's DownloadProvider UID, not RRBOX's own UID. This keeps
 * normal HEV self-bypass untouched while still allowing the exact same helper traffic to enter
 * either System or HEV TUN. Path validity is checked against sing-box sessionTraffic, and HEV adds
 * a second independent check against hev-socks5-tunnel's native TUN RX byte counter.
 */
internal object EngineBenchmarkProbe {
    const val HTTPS_HOST = "speed.cloudflare.com"
    const val HTTPS_ATTEMPTS = 3
    const val PREFLIGHT_BYTES = 64L * 1024L
    const val DOWNLOAD_BYTES = 2L * 1024L * 1024L
    const val HELPER_PACKAGE = "com.android.providers.downloads"

    private const val DOWNLOAD_TIMEOUT_MILLIS = 30_000L
    private const val ACCOUNTING_WAIT_MILLIS = 3_500L
    private const val POLL_MILLIS = 80L

    suspend fun httpsRound(
        context: Context,
        engine: String,
        attempt: Int,
        downloadBytes: Long = DOWNLOAD_BYTES
    ): HttpsProbeRound = withContext(Dispatchers.IO) {
        require(downloadBytes > 0L) { "HTTPS 测试字节必须大于 0" }

        val appContext = context.applicationContext
        val manager = appContext.getSystemService(DownloadManager::class.java)
            ?: return@withContext HttpsProbeRound(
                attempt = attempt,
                success = false,
                error = "系统 DownloadManager 不可用"
            )

        val proxyStart = RRVpnService.sessionTraffic.value.proxyDownloadTotal
        val requireHevNative = engine == PreferencesManager.TUN_ENGINE_HEV
        val nativeStartRx = if (requireHevNative) nativeRxBytes() else null
        if (requireHevNative && nativeStartRx == null) {
            return@withContext HttpsProbeRound(
                attempt = attempt,
                success = false,
                error = "HEV native 统计不可用"
            )
        }

        val nonce = "${SystemClock.elapsedRealtimeNanos()}-$engine-$attempt-$downloadBytes"
        val fileName = "rrbox-ab-$nonce.bin"
        val outputDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: return@withContext HttpsProbeRound(
                attempt = attempt,
                success = false,
                error = "测速临时目录不可用"
            )
        val outputFile = File(outputDir, fileName)
        runCatching { outputFile.delete() }

        val request = DownloadManager.Request(
            Uri.parse("https://$HTTPS_HOST/__down?bytes=$downloadBytes&rrbox=$nonce")
        )
            .setTitle("RRBOX A/B v2.2")
            .setDescription("$engine · ${formatBytes(downloadBytes)}")
            .setMimeType("application/octet-stream")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            .addRequestHeader("Cache-Control", "no-cache")
            .addRequestHeader("Accept-Encoding", "identity")
            .addRequestHeader("User-Agent", "RRBOX-Network-Lab/2.2")

        val startedNs = System.nanoTime()
        var downloadId: Long? = null
        var firstByteNs: Long? = null
        var finishedNs = startedNs
        var bytesReceived = 0L

        try {
            val id = manager.enqueue(request)
            downloadId = id
            val query = DownloadManager.Query().setFilterById(id)
            val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MILLIS
            var completed = false

            while (SystemClock.elapsedRealtime() < deadline && !completed) {
                manager.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("DownloadManager 任务消失")

                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    val currentBytes = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ).coerceAtLeast(0L)
                    if (currentBytes > bytesReceived) bytesReceived = currentBytes
                    if (bytesReceived > 0L && firstByteNs == null) firstByteNs = System.nanoTime()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            finishedNs = System.nanoTime()
                            completed = true
                        }

                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            throw IOException("DownloadManager 下载失败 reason=$reason")
                        }
                    }
                }
                if (!completed) delay(POLL_MILLIS)
            }

            if (!completed) throw IOException("DownloadManager 下载超时")
            if (bytesReceived < downloadBytes) {
                val actual = runCatching { outputFile.length() }.getOrDefault(bytesReceived)
                bytesReceived = max(bytesReceived, actual)
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
                firstByteMillis = nanosToMillis(firstNs - startedNs),
                downloadMillis = nanosToMillis(finishedNs - firstNs),
                bytesReceived = bytesReceived,
                downloadBps = downloadBps,
                proxyAccountedDownloadBytes = accounting.proxyBytes,
                nativeAccountedDownloadBytes = accounting.nativeRxBytes,
                nativePathVerified = accounting.nativeVerified,
                proxyPathVerified = accounting.pathVerified,
                protocol = "DownloadManager"
            )
        } catch (error: Throwable) {
            HttpsProbeRound(
                attempt = attempt,
                success = false,
                bytesReceived = bytesReceived,
                protocol = "DownloadManager",
                error = safeError(error)
            )
        } finally {
            downloadId?.let { id -> runCatching { manager.remove(id) } }
            runCatching { outputFile.delete() }
        }
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
        (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName).take(180)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.2f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private data class PathAccounting(
        val proxyBytes: Long,
        val nativeRxBytes: Long,
        val nativeVerified: Boolean,
        val pathVerified: Boolean
    )
}

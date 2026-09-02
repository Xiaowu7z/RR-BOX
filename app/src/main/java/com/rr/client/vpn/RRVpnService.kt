package com.rr.client.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.rr.client.RRApplication
import com.rr.client.core.BoxServiceWrapper
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSampler
import com.rr.client.traffic.TrafficSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android VPN lifecycle owner.
 *
 * The foreground notification is posted before any native work, while all
 * libbox startup/shutdown work runs off the main thread. A bad profile must
 * report an error and stop cleanly instead of taking down the app process.
 */
class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stopping = AtomicBoolean(false)

    private lateinit var notificationMgr: RRNotificationManager
    private var boxCore: BoxServiceWrapper? = null
    private var coreStartJob: Job? = null

    private var activeNodeTag = "RRVPS-Node"
    private var activeNodeId = ""

    private var startElapsedRealtime = 0L
    private var lastCalculationTime = 0L
    private var lastCoreDownTotal = -1L
    private var lastCoreUpTotal = -1L
    private var sessionDownTotal = 0L
    private var sessionUpTotal = 0L

    companion object {
        private const val TAG = "RRVpnService"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isStarting = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _currentSpeed = MutableStateFlow(TrafficSpeed())
        val currentSpeed: StateFlow<TrafficSpeed> = _currentSpeed.asStateFlow()

        private val _sessionTraffic = MutableStateFlow(SessionTraffic())
        val sessionTraffic: StateFlow<SessionTraffic> = _sessionTraffic.asStateFlow()

        const val EXTRA_CONFIG_JSON = "EXTRA_CONFIG_JSON"
        const val EXTRA_NODE_TAG = "EXTRA_NODE_TAG"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
    }

    inner class LocalBinder : Binder() {
        fun getService(): RRVpnService = this@RRVpnService
    }

    /** Preserve VpnService's system binder; return the local binder only for app binds. */
    override fun onBind(intent: Intent): IBinder {
        return super.onBind(intent) ?: binder
    }

    override fun onCreate() {
        super.onCreate()
        notificationMgr = RRNotificationManager(this)

        boxCore = BoxServiceWrapper(
            workingDir = filesDir,
            onLogReceived = { message -> recordCoreMessage(message) },
            onStatusUpdate = { status ->
                // gomobile callbacks arrive on native threads. Serialize all
                // traffic state and notification updates on the service scope.
                if (status.trafficAvailable) {
                    serviceScope.launch {
                        if (_isRunning.value || _isStarting.value) {
                            handleCoreTrafficStatus(status.uplinkTotal, status.downlinkTotal)
                        }
                    }
                }
            },
            onServiceStopRequested = {
                serviceScope.launch { stopVpn() }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RRNotificationManager.ACTION_STOP_VPN) {
            stopVpn()
            return START_NOT_STICKY
        }

        activeNodeTag = intent?.getStringExtra(EXTRA_NODE_TAG).orEmpty().ifBlank { "RRVPS-Node" }
        activeNodeId = intent?.getStringExtra(EXTRA_NODE_ID).orEmpty()

        // startForegroundService() has a strict deadline. Enter the foreground
        // before configuration parsing, command-socket startup, or TUN creation.
        val foregroundStarted = runCatching {
            startForeground(
                RRNotificationManager.NOTIFICATION_ID,
                notificationMgr.buildNotification(activeNodeTag, TrafficSpeed(), 0L)
            )
        }.onFailure { error ->
            publishError("无法启动 VPN 前台服务：${readableError(error)}")
        }.isSuccess

        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (configJson.isNullOrBlank()) {
            publishError("启动失败：代理配置为空")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        beginAsyncStart(configJson)
        return START_NOT_STICKY
    }

    private fun beginAsyncStart(configJson: String) {
        coreStartJob?.cancel()
        stopping.set(false)
        _lastError.value = null
        _isRunning.value = false
        _isStarting.value = true
        _currentSpeed.value = TrafficSpeed()
        _sessionTraffic.value = SessionTraffic()

        startElapsedRealtime = SystemClock.elapsedRealtime()
        lastCalculationTime = startElapsedRealtime
        lastCoreDownTotal = -1L
        lastCoreUpTotal = -1L
        sessionDownTotal = 0L
        sessionUpTotal = 0L

        coreStartJob = serviceScope.launch(Dispatchers.IO) {
            val started = runCatching {
                boxCore?.startService(configJson, this@RRVpnService) == true
            }.onFailure { error ->
                recordCoreMessage("内核启动异常：${readableError(error)}")
            }.getOrDefault(false)

            withContext(Dispatchers.Main.immediate) {
                if (started) {
                    _isStarting.value = false
                    _isRunning.value = true
                    notificationMgr.updateNotification(activeNodeTag, _currentSpeed.value, 0L)
                } else {
                    val detail = boxCore?.lastStartError()
                        ?.takeIf { it.isNotBlank() }
                        ?: "sing-box 未能启动，请检查节点配置"
                    publishError("连接失败：$detail")
                    stopVpn(persistSession = false)
                }
            }
        }
    }

    /**
     * CommandStatus exposes monotonic core totals. The first sample is only a
     * baseline. Counter resets start a new segment and cannot create a
     * negative value or a false multi-GB/s spike.
     */
    private fun handleCoreTrafficStatus(uplinkTotal: Long, downlinkTotal: Long) {
        val now = SystemClock.elapsedRealtime()
        val deltaTimeMs = (now - lastCalculationTime).coerceAtLeast(1L)

        val currentDown = downlinkTotal.coerceAtLeast(0L)
        val currentUp = uplinkTotal.coerceAtLeast(0L)

        if (lastCoreDownTotal < 0L || lastCoreUpTotal < 0L) {
            lastCoreDownTotal = currentDown
            lastCoreUpTotal = currentUp
            lastCalculationTime = now
            return
        }

        val downDiff = if (currentDown >= lastCoreDownTotal) {
            currentDown - lastCoreDownTotal
        } else {
            currentDown
        }
        val upDiff = if (currentUp >= lastCoreUpTotal) {
            currentUp - lastCoreUpTotal
        } else {
            currentUp
        }

        // 2 GB within one status interval is treated as a corrupted frame. We
        // still advance the baseline so one bad sample cannot poison later data.
        val safeDownDiff = downDiff.takeIf { it <= 2_000_000_000L } ?: 0L
        val safeUpDiff = upDiff.takeIf { it <= 2_000_000_000L } ?: 0L

        sessionDownTotal += safeDownDiff
        sessionUpTotal += safeUpDiff

        val downSpeed = (safeDownDiff * 1_000L) / deltaTimeMs
        val upSpeed = (safeUpDiff * 1_000L) / deltaTimeMs
        val speed = TrafficSpeed(
            uploadBytesPerSec = upSpeed,
            downloadBytesPerSec = downSpeed,
            formattedDownSpeed = TrafficSampler.formatSpeed(downSpeed),
            formattedUpSpeed = TrafficSampler.formatSpeed(upSpeed)
        )
        _currentSpeed.value = speed

        val durationSec = ((now - startElapsedRealtime) / 1_000L).coerceAtLeast(0L)
        _sessionTraffic.value = SessionTraffic(
            proxyDownloadTotal = sessionDownTotal,
            proxyUploadTotal = sessionUpTotal,
            durationSeconds = durationSec
        )
        notificationMgr.updateNotification(activeNodeTag, speed, durationSec)

        lastCalculationTime = now
        lastCoreDownTotal = currentDown
        lastCoreUpTotal = currentUp
    }

    private fun publishError(message: String) {
        Log.e(TAG, message)
        _lastError.value = message
        _isStarting.value = false
        _isRunning.value = false
        recordCoreMessage(message)
    }

    private fun recordCoreMessage(message: String) {
        Log.i(TAG, message)
        runCatching {
            val safe = message.replace(
                Regex("(?i)(password|uuid|token|secret)\\s*[:=]\\s*\\S+"),
                "$1=<redacted>"
            )
            File(filesDir, "last-core.log").appendText("${System.currentTimeMillis()} $safe\n")
        }.onFailure { Log.d(TAG, "Unable to persist core message", it) }
    }

    private fun stopVpn(persistSession: Boolean = true) {
        if (!stopping.compareAndSet(false, true)) return

        coreStartJob?.cancel()
        coreStartJob = null
        val finalSession = _sessionTraffic.value

        serviceScope.launch(Dispatchers.IO) {
            runCatching { boxCore?.stopService() }
                .onFailure { Log.w(TAG, "Stopping sing-box failed", it) }

            if (persistSession && finalSession.durationSeconds > 0L) {
                runCatching {
                    RRApplication.instance.database.trafficDao().insertTraffic(
                        TrafficHistoryEntity(
                            nodeTag = activeNodeTag,
                            proxyDownload = finalSession.proxyDownloadTotal,
                            proxyUpload = finalSession.proxyUploadTotal,
                            directDownload = finalSession.directDownloadTotal,
                            directUpload = finalSession.directUploadTotal,
                            durationSeconds = finalSession.durationSeconds,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }.onFailure { Log.w(TAG, "Persisting traffic session failed", it) }
            }

            withContext(Dispatchers.Main.immediate) {
                _isRunning.value = false
                _isStarting.value = false
                _currentSpeed.value = TrafficSpeed()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                stopping.set(false)
            }
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        coreStartJob?.cancel()
        runCatching { boxCore?.stopService() }
            .onFailure { Log.w(TAG, "Core cleanup during destroy failed", it) }
        _isRunning.value = false
        _isStarting.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun readableError(error: Throwable): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf { message -> message.isNotEmpty() } }
            .distinct()
            .take(3)
            .joinToString("；")
            .ifBlank { error.javaClass.simpleName }
}

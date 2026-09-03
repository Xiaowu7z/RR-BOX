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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreMutex = Mutex()

    private lateinit var notificationMgr: RRNotificationManager
    private var boxCore: BoxServiceWrapper? = null
    private var startJob: Job? = null
    private var stopping = false
    private var sessionPersisted = false
    private var requestGeneration = 0L

    private var activeConfigJson: String? = null
    private var activeNodeTag = "Default"
    private var activeNodeId = ""

    private var startElapsedRealtime = 0L
    private var lastCalculationTime = 0L
    private var lastProxyDownTotal = 0L
    private var lastProxyUpTotal = 0L
    private var carriedProxyDown = 0L
    private var carriedProxyUp = 0L
    private var hasTrafficBaseline = false

    companion object {
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

        private const val TAG = "RRVpnService"

        fun clearLastError() {
            _lastError.value = null
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RRVpnService = this@RRVpnService
    }

    override fun onBind(intent: Intent): IBinder = super.onBind(intent) ?: binder

    override fun onCreate() {
        super.onCreate()
        notificationMgr = RRNotificationManager(this)
        boxCore = BoxServiceWrapper(
            workingDir = filesDir,
            onLogReceived = { line -> Log.d(TAG, line) },
            onStatusUpdate = { status ->
                if (status.trafficAvailable) {
                    handleRealTrafficStatus(status.uplinkTotal, status.downlinkTotal)
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RRNotificationManager.ACTION_STOP_VPN -> {
                stopVpn(persistTraffic = true)
                return START_NOT_STICKY
            }

            RRNotificationManager.ACTION_RESTART_VPN -> {
                intent.getStringExtra(EXTRA_CONFIG_JSON)?.takeIf(String::isNotBlank)?.let {
                    activeConfigJson = it
                }
                intent.getStringExtra(EXTRA_NODE_TAG)?.takeIf(String::isNotBlank)?.let {
                    activeNodeTag = it
                }
                if (intent.hasExtra(EXTRA_NODE_ID)) {
                    activeNodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
                }

                val config = activeConfigJson
                if (config.isNullOrBlank()) {
                    _lastError.value = "没有当前运行配置可供重启"
                    Log.e(TAG, _lastError.value.orEmpty())
                    return START_NOT_STICKY
                }

                ensureForeground("$activeNodeTag · 正在重启")
                launchCore(config, restarting = true)
                return START_NOT_STICKY
            }
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        activeNodeTag = intent?.getStringExtra(EXTRA_NODE_TAG)?.takeIf(String::isNotBlank)
            ?: activeNodeTag.ifBlank { "RRBOX-Node" }
        if (intent?.hasExtra(EXTRA_NODE_ID) == true) {
            activeNodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
        }

        ensureForeground("$activeNodeTag · 正在启动")

        if (configJson.isNullOrBlank()) {
            _lastError.value = "没有收到可运行的 sing-box 配置"
            Log.e(TAG, _lastError.value.orEmpty())
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        activeConfigJson = configJson
        val restarting = _isRunning.value || _isStarting.value || boxCore?.isCoreRunning() == true
        launchCore(configJson, restarting)
        return START_NOT_STICKY
    }

    private fun ensureForeground(title: String) {
        startForeground(
            RRNotificationManager.NOTIFICATION_ID,
            notificationMgr.buildNotification(title, TrafficSpeed(), 0L)
        )
    }

    private fun launchCore(configJson: String, restarting: Boolean) {
        val generation = ++requestGeneration
        stopping = false
        _lastError.value = null
        _isStarting.value = true

        val previousSession = _sessionTraffic.value
        startJob?.cancel()
        startJob = serviceScope.launch {
            val started = withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    if (generation != requestGeneration) return@withLock false
                    if (restarting || boxCore?.isCoreRunning() == true) {
                        if (previousSession.durationSeconds > 0L) {
                            persistSessionOnce(previousSession)
                        }
                        boxCore?.stopService()
                    }
                    boxCore?.startService(configJson, this@RRVpnService) ?: false
                }
            }

            if (generation != requestGeneration) return@launch

            if (started) {
                activeConfigJson = configJson
                sessionPersisted = false
                resetTrafficState()
                _isStarting.value = false
                _isRunning.value = true
                notificationMgr.updateNotification(activeNodeTag, TrafficSpeed(), 0L)
                Log.i(TAG, "VPN tunnel started: $activeNodeTag")
            } else {
                val reason = boxCore?.lastError ?: "sing-box 内核未能启动"
                _lastError.value = reason
                Log.e(TAG, reason)
                _isStarting.value = false
                stopVpn(persistTraffic = false)
            }
        }
    }

    override fun onRevoke() {
        _lastError.value = "Android 已撤销 VPN 权限"
        Log.w(TAG, _lastError.value.orEmpty())
        stopVpn(persistTraffic = true)
        super.onRevoke()
    }

    private fun resetTrafficState() {
        startElapsedRealtime = SystemClock.elapsedRealtime()
        lastCalculationTime = startElapsedRealtime
        lastProxyDownTotal = 0L
        lastProxyUpTotal = 0L
        carriedProxyDown = 0L
        carriedProxyUp = 0L
        hasTrafficBaseline = false
        _currentSpeed.value = TrafficSpeed()
        _sessionTraffic.value = SessionTraffic()
    }

    private fun handleRealTrafficStatus(uplinkTotal: Long, downlinkTotal: Long) {
        val now = SystemClock.elapsedRealtime()
        val rawDown = downlinkTotal.coerceAtLeast(0L)
        val rawUp = uplinkTotal.coerceAtLeast(0L)

        if (!hasTrafficBaseline) {
            hasTrafficBaseline = true
            lastCalculationTime = now
            lastProxyDownTotal = rawDown
            lastProxyUpTotal = rawUp
            updateTraffic(rawDown, rawUp, now, TrafficSpeed())
            return
        }

        val deltaTimeMs = now - lastCalculationTime
        if (deltaTimeMs < 500L) return

        val downDiff: Long
        val upDiff: Long

        if (rawDown < lastProxyDownTotal) {
            carriedProxyDown += lastProxyDownTotal
            downDiff = rawDown
        } else {
            downDiff = rawDown - lastProxyDownTotal
        }

        if (rawUp < lastProxyUpTotal) {
            carriedProxyUp += lastProxyUpTotal
            upDiff = rawUp
        } else {
            upDiff = rawUp - lastProxyUpTotal
        }

        val speed = TrafficSpeed(
            uploadBytesPerSec = calculateRate(upDiff, deltaTimeMs),
            downloadBytesPerSec = calculateRate(downDiff, deltaTimeMs)
        )

        lastCalculationTime = now
        lastProxyDownTotal = rawDown
        lastProxyUpTotal = rawUp
        updateTraffic(
            carriedProxyDown + rawDown,
            carriedProxyUp + rawUp,
            now,
            speed
        )
    }

    private fun calculateRate(bytes: Long, elapsedMs: Long): Long {
        if (bytes <= 0L || elapsedMs <= 0L) return 0L
        return runCatching { Math.multiplyExact(bytes, 1000L) / elapsedMs }
            .getOrElse {
                (bytes.toDouble() * 1000.0 / elapsedMs.toDouble())
                    .coerceAtMost(Long.MAX_VALUE.toDouble())
                    .toLong()
            }
    }

    private fun updateTraffic(
        downloadTotal: Long,
        uploadTotal: Long,
        now: Long,
        speed: TrafficSpeed
    ) {
        val durationSeconds = (now - startElapsedRealtime).coerceAtLeast(0L) / 1000L
        _currentSpeed.value = speed
        _sessionTraffic.value = SessionTraffic(
            proxyDownloadTotal = downloadTotal.coerceAtLeast(0L),
            proxyUploadTotal = uploadTotal.coerceAtLeast(0L),
            durationSeconds = durationSeconds
        )
        if (_isRunning.value) {
            notificationMgr.updateNotification(activeNodeTag, speed, durationSeconds)
        }
    }

    private fun stopVpn(persistTraffic: Boolean) {
        if (stopping) return
        stopping = true
        ++requestGeneration
        startJob?.cancel()
        startJob = null

        val finalSession = _sessionTraffic.value
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    if (persistTraffic) persistSessionOnce(finalSession)
                    boxCore?.stopService()
                }
            }
            activeConfigJson = null
            _isStarting.value = false
            _isRunning.value = false
            _currentSpeed.value = TrafficSpeed()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun persistSessionOnce(session: SessionTraffic) {
        if (sessionPersisted || session.durationSeconds <= 0L) return
        sessionPersisted = true
        runCatching {
            RRApplication.instance.database.trafficDao().insertTraffic(
                TrafficHistoryEntity(
                    nodeTag = activeNodeTag,
                    proxyDownload = session.proxyDownloadTotal,
                    proxyUpload = session.proxyUploadTotal,
                    directDownload = session.directDownloadTotal,
                    directUpload = session.directUploadTotal,
                    durationSeconds = session.durationSeconds,
                    timestamp = System.currentTimeMillis()
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to persist traffic history", error)
        }
    }

    override fun onDestroy() {
        ++requestGeneration
        startJob?.cancel()
        startJob = null
        runCatching { boxCore?.stopService() }
        _isStarting.value = false
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }
}

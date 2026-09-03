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
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSpeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreMutex = Mutex()
    private val requestGeneration = AtomicLong(0L)

    private lateinit var notificationMgr: RRNotificationManager
    private lateinit var runtimeStateStore: VpnRuntimeStateStore
    private var boxCore: BoxServiceWrapper? = null
    private var stopping = false
    private var sessionPersisted = false

    private var activeConfigJson: String? = null
    private var activeNodeTag = "Default"
    private var activeNodeId = ""
    private var activePerAppMode = PerAppPolicyResolver.MODE_ALL
    private var activeSelectedPackages: Set<String> = emptySet()

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
        const val EXTRA_PER_APP_MODE = "EXTRA_PER_APP_MODE"
        const val EXTRA_SELECTED_PACKAGES = "EXTRA_SELECTED_PACKAGES"

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
        runtimeStateStore = VpnRuntimeStateStore(this)
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
                stopVpn(persistTraffic = true, clearRuntimeState = true)
                return START_NOT_STICKY
            }

            RRNotificationManager.ACTION_RESTART_VPN -> {
                applyIntentOverrides(intent)
                if (activeConfigJson.isNullOrBlank()) {
                    restoreRuntimeState(runtimeStateStore.load())
                }
                val config = activeConfigJson
                if (config.isNullOrBlank()) {
                    _lastError.value = "没有可用于重启的运行配置"
                    Log.e(TAG, _lastError.value.orEmpty())
                    return START_NOT_STICKY
                }
                ensureForeground("$activeNodeTag · 正在重启")
                launchCore(config, restarting = true)
                return START_STICKY
            }
        }

        val incomingConfig = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        if (!incomingConfig.isNullOrBlank()) {
            activeConfigJson = incomingConfig
            activeNodeTag = intent.getStringExtra(EXTRA_NODE_TAG)?.takeIf(String::isNotBlank)
                ?: "RRBOX-Node"
            activeNodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
            activePerAppMode = intent.getStringExtra(EXTRA_PER_APP_MODE)
                ?: PerAppPolicyResolver.MODE_ALL
            activeSelectedPackages = intent.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty()

            ensureForeground("$activeNodeTag · 正在启动")
            val restarting = _isStarting.value || _isRunning.value || boxCore?.isCoreRunning() == true
            launchCore(incomingConfig, restarting = restarting)
            return START_STICKY
        }

        // START_STICKY restarts can arrive with a null intent after Android
        // reclaims the process. Recover only a previously successful session;
        // an explicit user disconnect clears this store first.
        val recovered = runtimeStateStore.load()
        if (recovered != null) {
            restoreRuntimeState(recovered)
            ensureForeground("$activeNodeTag · 正在恢复")
            Log.i(TAG, "Recovering sticky VPN session for $activeNodeTag")
            launchCore(recovered.configJson, restarting = false)
            return START_STICKY
        }

        _lastError.value = "没有收到可运行或可恢复的 sing-box 配置"
        Log.w(TAG, _lastError.value.orEmpty())
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun restoreRuntimeState(state: VpnRuntimeState?) {
        if (state == null) return
        activeConfigJson = state.configJson
        activeNodeTag = state.nodeTag.ifBlank { "RRBOX-Node" }
        activeNodeId = state.nodeId
        activePerAppMode = state.perAppMode
        activeSelectedPackages = state.selectedPackages
    }

    private fun applyIntentOverrides(intent: Intent) {
        intent.getStringExtra(EXTRA_CONFIG_JSON)?.takeIf(String::isNotBlank)?.let {
            activeConfigJson = it
        }
        intent.getStringExtra(EXTRA_NODE_TAG)?.takeIf(String::isNotBlank)?.let {
            activeNodeTag = it
        }
        if (intent.hasExtra(EXTRA_NODE_ID)) {
            activeNodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
        }
        intent.getStringExtra(EXTRA_PER_APP_MODE)?.takeIf(String::isNotBlank)?.let {
            activePerAppMode = it
        }
        if (intent.hasExtra(EXTRA_SELECTED_PACKAGES)) {
            activeSelectedPackages = intent.getStringArrayListExtra(EXTRA_SELECTED_PACKAGES)
                ?.filter(String::isNotBlank)
                ?.toSet()
                .orEmpty()
        }
    }

    private fun ensureForeground(title: String) {
        startForeground(
            RRNotificationManager.NOTIFICATION_ID,
            notificationMgr.buildNotification(title, TrafficSpeed(), 0L)
        )
    }

    private fun launchCore(configJson: String, restarting: Boolean) {
        val generation = requestGeneration.incrementAndGet()
        stopping = false
        _lastError.value = null
        _isStarting.value = true
        _isRunning.value = false

        val previousSession = _sessionTraffic.value
        if (restarting) {
            notificationMgr.updateNotification("$activeNodeTag · 正在重启", TrafficSpeed(), 0L)
        }

        serviceScope.launch {
            val started = withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    // Drop obsolete queued requests. A request already executing
                    // is allowed to finish; the newest request then rebuilds once.
                    if (generation != requestGeneration.get()) return@withLock false

                    if (restarting && previousSession.durationSeconds > 0L) {
                        persistSessionOnce(previousSession)
                    }
                    if (restarting || boxCore?.isCoreRunning() == true) {
                        boxCore?.stopService()
                    }

                    boxCore?.setPerAppPolicy(activePerAppMode, activeSelectedPackages)
                    boxCore?.startService(configJson, this@RRVpnService) ?: false
                }
            }

            if (generation != requestGeneration.get()) {
                Log.d(TAG, "Ignoring stale VPN core result generation=$generation")
                return@launch
            }

            if (started) {
                activeConfigJson = configJson
                sessionPersisted = false
                resetTrafficState()
                runtimeStateStore.save(
                    VpnRuntimeState(
                        configJson = configJson,
                        nodeTag = activeNodeTag,
                        nodeId = activeNodeId,
                        perAppMode = activePerAppMode,
                        selectedPackages = activeSelectedPackages
                    )
                )
                _isStarting.value = false
                _isRunning.value = true
                notificationMgr.updateNotification(activeNodeTag, TrafficSpeed(), 0L)
                Log.i(
                    TAG,
                    "VPN tunnel started: $activeNodeTag, mode=$activePerAppMode, selected=${activeSelectedPackages.size}"
                )
            } else {
                val reason = boxCore?.lastError ?: "sing-box 内核未能启动"
                _lastError.value = reason
                Log.e(TAG, reason)
                _isStarting.value = false
                stopVpn(persistTraffic = false, clearRuntimeState = true)
            }
        }
    }

    override fun onRevoke() {
        _lastError.value = "Android 已撤销 VPN 权限"
        Log.w(TAG, _lastError.value.orEmpty())
        stopVpn(persistTraffic = true, clearRuntimeState = true)
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

    private fun stopVpn(persistTraffic: Boolean, clearRuntimeState: Boolean) {
        if (stopping) return
        stopping = true
        requestGeneration.incrementAndGet()
        val finalSession = _sessionTraffic.value

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    if (persistTraffic) persistSessionOnce(finalSession)
                    boxCore?.stopService()
                    if (clearRuntimeState) runtimeStateStore.clear()
                }
            }
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
        requestGeneration.incrementAndGet()
        runCatching { boxCore?.stopService() }
        _isStarting.value = false
        _isRunning.value = false
        serviceScope.cancel()
        super.onDestroy()
    }
}

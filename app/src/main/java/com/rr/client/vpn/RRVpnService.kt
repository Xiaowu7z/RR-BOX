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
import com.rr.client.core.HevConfigAdapter
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.storage.PreferencesManager
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSpeed
import io.nekohasekai.libbox.Libbox
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private var hevEngine: HevVpnEngine? = null
    private var startJob: Job? = null
    private var stopping = false
    private var sessionPersisted = false
    private var requestGeneration = 0L

    /** Always the canonical stable system-TUN config, never the HEV-adapted config. */
    private var activeConfigJson: String? = null
    private var activeNodeTag = "Default"
    private var activeNodeId = ""
    private var activeEngine = PreferencesManager.TUN_ENGINE_SYSTEM

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

        private val _activeRuntimeNodeId = MutableStateFlow<String?>(null)
        val activeRuntimeNodeId: StateFlow<String?> = _activeRuntimeNodeId.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val _currentSpeed = MutableStateFlow(TrafficSpeed())
        val currentSpeed: StateFlow<TrafficSpeed> = _currentSpeed.asStateFlow()

        private val _sessionTraffic = MutableStateFlow(SessionTraffic())
        val sessionTraffic: StateFlow<SessionTraffic> = _sessionTraffic.asStateFlow()

        private val _engineRestartMeasurement = MutableStateFlow(EngineRestartMeasurement())
        val engineRestartMeasurement: StateFlow<EngineRestartMeasurement> =
            _engineRestartMeasurement.asStateFlow()

        private var serviceRef: WeakReference<RRVpnService>? = null

        const val EXTRA_CONFIG_JSON = "EXTRA_CONFIG_JSON"
        const val EXTRA_NODE_TAG = "EXTRA_NODE_TAG"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
        const val EXTRA_HEV_BENCHMARK_SELF_TRAFFIC = "EXTRA_HEV_BENCHMARK_SELF_TRAFFIC"

        /** Restart the current canonical config after a forwarding-engine preference change. */
        const val ACTION_RESTART_ACTIVE_ENGINE = "com.rr.client.action.RESTART_ACTIVE_ENGINE"
        /** Lab-only controlled failure: stop the local data plane but keep desired-running state. */
        const val ACTION_LAB_DROP_DATA_PLANE = "com.rr.client.action.LAB_DROP_DATA_PLANE"

        private const val TAG = "RRVpnService"

        fun clearLastError() {
            _lastError.value = null
        }

        /** Real local data-plane status, not just the UI StateFlow flag. */
        fun isDataPlaneHealthy(): Boolean = serviceRef?.get()?.isDataPlaneHealthyInternal() == true
    }

    inner class LocalBinder : Binder() {
        fun getService(): RRVpnService = this@RRVpnService
    }

    override fun onBind(intent: Intent): IBinder = super.onBind(intent) ?: binder

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
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
        hevEngine = HevVpnEngine(
            vpnService = this,
            workingDir = filesDir,
            onLog = { line -> Log.d(TAG, line) }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand startId=$startId action=${intent?.action ?: "START"}")

        when (intent?.action) {
            RRNotificationManager.ACTION_STOP_VPN -> {
                VpnConnectionIntentStore.setDesiredRunning(this, false)
                stopVpn(persistTraffic = true)
                return START_NOT_STICKY
            }

            ACTION_LAB_DROP_DATA_PLANE -> {
                dropDataPlaneForLab()
                return START_NOT_STICKY
            }

            ACTION_RESTART_ACTIVE_ENGINE -> {
                val config = activeConfigJson
                if (config.isNullOrBlank()) {
                    _lastError.value = "没有当前运行配置可供切换转发引擎"
                    Log.e(TAG, _lastError.value.orEmpty())
                    return START_NOT_STICKY
                }
                VpnConnectionIntentStore.setDesiredRunning(this, true)
                val benchmarkSelf = intent.getBooleanExtra(
                    EXTRA_HEV_BENCHMARK_SELF_TRAFFIC,
                    false
                )
                ensureForeground(
                    if (benchmarkSelf) "$activeNodeTag · HEV A/B" else "$activeNodeTag · 正在切换引擎"
                )
                launchCore(
                    stableConfigJson = config,
                    restarting = true,
                    hevBenchmarkSelfTraffic = benchmarkSelf
                )
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
                _activeRuntimeNodeId.value = activeNodeId.takeIf(String::isNotBlank)

                val config = activeConfigJson
                if (config.isNullOrBlank()) {
                    _lastError.value = "没有当前运行配置可供重启"
                    Log.e(TAG, _lastError.value.orEmpty())
                    return START_NOT_STICKY
                }

                VpnConnectionIntentStore.setDesiredRunning(this, true)
                ensureForeground("$activeNodeTag · 正在重启")
                launchCore(config, restarting = true)
                return START_NOT_STICKY
            }
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        val requestedNodeTag = intent?.getStringExtra(EXTRA_NODE_TAG)?.takeIf(String::isNotBlank)
        val requestedNodeId = if (intent?.hasExtra(EXTRA_NODE_ID) == true) {
            intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
        } else {
            null
        }

        val hasLiveDataPlane = _isRunning.value || _isStarting.value ||
            boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true
        val duplicateEquivalentStart = hasLiveDataPlane &&
            !configJson.isNullOrBlank() &&
            configJson == activeConfigJson

        requestedNodeTag?.let { activeNodeTag = it }
        requestedNodeId?.let {
            activeNodeId = it
            _activeRuntimeNodeId.value = it.takeIf(String::isNotBlank)
        }

        if (duplicateEquivalentStart) {
            VpnConnectionIntentStore.setDesiredRunning(this, true)
            val stateLabel = if (_isRunning.value) "已连接" else "正在启动"
            ensureForeground("$activeNodeTag · $stateLabel")
            Log.d(
                TAG,
                "Ignoring duplicate equivalent VPN start request: node=$activeNodeTag id=$activeNodeId"
            )
            return START_NOT_STICKY
        }

        activeNodeTag = requestedNodeTag ?: activeNodeTag.ifBlank { "RRBOX-Node" }

        ensureForeground("$activeNodeTag · 正在启动")

        if (configJson.isNullOrBlank()) {
            _activeRuntimeNodeId.value = null
            VpnConnectionIntentStore.setDesiredRunning(this, false)
            _lastError.value = "没有收到可运行的 sing-box 配置"
            Log.e(TAG, _lastError.value.orEmpty())
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        VpnConnectionIntentStore.setDesiredRunning(this, true)
        activeConfigJson = configJson
        val restarting = _isRunning.value || _isStarting.value ||
            boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true
        launchCore(configJson, restarting)
        return START_NOT_STICKY
    }

    private fun ensureForeground(title: String) {
        startForeground(
            RRNotificationManager.NOTIFICATION_ID,
            notificationMgr.buildNotification(title, TrafficSpeed(), 0L)
        )
    }

    private fun launchCore(
        stableConfigJson: String,
        restarting: Boolean,
        hevBenchmarkSelfTraffic: Boolean = false
    ) {
        val measurementStartedAt = SystemClock.elapsedRealtime()
        val generation = ++requestGeneration
        stopping = false
        _lastError.value = null
        _isStarting.value = true

        val previousSession = _sessionTraffic.value
        startJob?.cancel()
        startJob = serviceScope.launch {
            var resolvedEngine = PreferencesManager.TUN_ENGINE_SYSTEM

            val started = withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    if (generation != requestGeneration) return@withLock false

                    if (restarting || boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true) {
                        if (previousSession.durationSeconds > 0L) {
                            persistSessionOnce(previousSession)
                        }
                        stopDataPlane()
                    }

                    val prefs = RRApplication.instance.preferencesManager
                    resolvedEngine = runCatching { prefs.tunEngine.first() }
                        .getOrDefault(PreferencesManager.TUN_ENGINE_SYSTEM)

                    if (resolvedEngine == PreferencesManager.TUN_ENGINE_HEV) {
                        val runtime = runCatching { HevConfigAdapter.adapt(stableConfigJson) }
                            .getOrElse { error ->
                                Log.e(TAG, "Unable to adapt config for HEV", error)
                                return@withLock false
                            }

                        runCatching { Libbox.checkConfig(runtime.configJson) }
                            .getOrElse { error ->
                                Log.e(TAG, "HEV sing-box config validation failed", error)
                                return@withLock false
                            }

                        val coreStarted = boxCore?.startService(runtime.configJson, this@RRVpnService) == true
                        if (!coreStarted) return@withLock false

                        val hevStarted = hevEngine?.start(
                            policy = runtime.perAppPolicy,
                            includeSelfForBenchmark = hevBenchmarkSelfTraffic
                        ) == true
                        if (!hevStarted) {
                            boxCore?.stopService()
                            return@withLock false
                        }
                        true
                    } else {
                        boxCore?.startService(stableConfigJson, this@RRVpnService) ?: false
                    }
                }
            }

            if (generation != requestGeneration) return@launch

            if (started) {
                activeConfigJson = stableConfigJson
                activeEngine = resolvedEngine
                sessionPersisted = false
                resetTrafficState()
                _isStarting.value = false
                _isRunning.value = true
                _activeRuntimeNodeId.value = activeNodeId.takeIf(String::isNotBlank)
                VpnConnectionIntentStore.setDesiredRunning(this@RRVpnService, true)
                notificationMgr.updateNotification(displayNodeTag(), TrafficSpeed(), 0L)
                publishRestartMeasurement(
                    measurementStartedAt = measurementStartedAt,
                    success = true,
                    engine = activeEngine
                )
                serviceScope.launch(Dispatchers.IO) {
                    refreshRuntimeCache(stableConfigJson)
                }
                Log.i(
                    TAG,
                    "VPN tunnel started: $activeNodeTag · engine=$activeEngine" +
                        if (hevBenchmarkSelfTraffic && activeEngine == PreferencesManager.TUN_ENGINE_HEV) {
                            " · benchmark-self-route-v2.5"
                        } else {
                            ""
                        }
                )
            } else {
                val reason = hevEngine?.lastError
                    ?: boxCore?.lastError
                    ?: if (resolvedEngine == PreferencesManager.TUN_ENGINE_HEV) {
                        "HEV 极速引擎未能启动"
                    } else {
                        "sing-box 内核未能启动"
                    }
                _lastError.value = reason
                Log.e(TAG, reason)
                _isStarting.value = false
                publishRestartMeasurement(
                    measurementStartedAt = measurementStartedAt,
                    success = false,
                    engine = resolvedEngine
                )

                if (hevBenchmarkSelfTraffic) {
                    withContext(Dispatchers.IO) {
                        coreMutex.withLock { stopDataPlane() }
                    }
                    _isRunning.value = false
                    _currentSpeed.value = TrafficSpeed()
                    _sessionTraffic.value = SessionTraffic()
                    ensureForeground("$activeNodeTag · A/B 失败，正在恢复")
                    Log.w(TAG, "HEV benchmark restart failed; canonical config preserved for recovery")
                } else {
                    VpnConnectionIntentStore.setDesiredRunning(this@RRVpnService, false)
                    stopVpn(persistTraffic = false)
                }
            }
        }
    }

    private suspend fun refreshRuntimeCache(stableConfigJson: String) {
        runCatching {
            val prefs = RRApplication.instance.preferencesManager
            val perAppMode = prefs.perAppMode.first()
            val selectedPackages = when (perAppMode) {
                PerAppPolicyResolver.MODE_ALLOW_LIST -> prefs.proxySelectedAppPackages.first()
                PerAppPolicyResolver.MODE_DISALLOW_LIST -> prefs.bypassSelectedAppPackages.first()
                else -> emptySet()
            }
            VpnRuntimeStateStore(this).save(
                VpnRuntimeState(
                    configJson = stableConfigJson,
                    nodeTag = activeNodeTag,
                    nodeId = activeNodeId,
                    perAppMode = perAppMode,
                    selectedPackages = selectedPackages,
                    smartRouting = prefs.smartRouting.first(),
                    fastForwarding = prefs.fastForwarding.first()
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh validated runtime cache", error)
        }
    }

    private fun publishRestartMeasurement(
        measurementStartedAt: Long,
        success: Boolean,
        engine: String
    ) {
        val previous = _engineRestartMeasurement.value
        _engineRestartMeasurement.value = EngineRestartMeasurement(
            serial = previous.serial + 1L,
            durationMillis = (SystemClock.elapsedRealtime() - measurementStartedAt).coerceAtLeast(1L),
            success = success,
            engine = engine
        )
    }

    private fun stopDataPlane() {
        hevEngine?.stop()
        boxCore?.stopService()
    }

    private fun isDataPlaneHealthyInternal(): Boolean = when (activeEngine) {
        PreferencesManager.TUN_ENGINE_HEV ->
            boxCore?.isCoreRunning() == true && hevEngine?.isRunning == true
        else -> boxCore?.isCoreRunning() == true
    }

    private fun dropDataPlaneForLab() {
        if (!VpnConnectionIntentStore.isDesiredRunning(this) || activeConfigJson.isNullOrBlank()) {
            Log.w(TAG, "Ignoring lab data-plane drop: no desired active runtime")
            return
        }
        val generation = ++requestGeneration
        startJob?.cancel()
        startJob = null
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                coreMutex.withLock { stopDataPlane() }
            }
            if (generation != requestGeneration) return@launch
            _isStarting.value = false
            _isRunning.value = false
            _currentSpeed.value = TrafficSpeed()
            ensureForeground("$activeNodeTag · 恢复演练")
            Log.w(TAG, "LAB: local data plane intentionally stopped; desired-running state preserved")
        }
    }

    private fun displayNodeTag(): String = if (activeEngine == PreferencesManager.TUN_ENGINE_HEV) {
        "$activeNodeTag · HEV"
    } else {
        activeNodeTag
    }

    override fun onRevoke() {
        VpnConnectionIntentStore.setDesiredRunning(this, false)
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
            notificationMgr.updateNotification(displayNodeTag(), speed, durationSeconds)
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
                    stopDataPlane()
                }
            }
            activeConfigJson = null
            activeEngine = PreferencesManager.TUN_ENGINE_SYSTEM
            _isStarting.value = false
            _isRunning.value = false
            _activeRuntimeNodeId.value = null
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
        runCatching { hevEngine?.stop() }
        runCatching { boxCore?.stopService() }
        _isStarting.value = false
        _isRunning.value = false
        _activeRuntimeNodeId.value = null
        if (serviceRef?.get() === this) serviceRef = null
        serviceScope.cancel()
        super.onDestroy()
    }
}

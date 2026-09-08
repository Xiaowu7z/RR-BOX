package com.rr.client.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.rr.client.RRApplication
import com.rr.client.core.BoxServiceWrapper
import com.rr.client.core.HevConfigAdapter
import com.rr.client.routing.ChinaRuleSetManager
import com.rr.client.routing.PerAppPolicyResolver
import com.rr.client.storage.PreferencesManager
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSpeed
import io.nekohasekai.libbox.Libbox
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notificationMgr: RRNotificationManager
    private var boxCore: BoxServiceWrapper? = null
    private var hevEngine: HevVpnEngine? = null
    private var rootEngine: RootVpnEngine? = null
    private var startJob: Job? = null
    private var stopping = false
    private var sessionPersisted = false
    @Volatile private var requestGeneration = 0L

    /** Always the canonical stable system-TUN config, never an engine-adapted config. */
    private var activeConfigJson: String? = null
    private var activeNodeTag = "Default"
    private var activeNodeId = ""
    private var activeEngine = PreferencesManager.TUN_ENGINE_SYSTEM
    @Volatile private var requestedEngine = PreferencesManager.TUN_ENGINE_SYSTEM

    private data class PreparedRuleActivation(
        val candidateGeneration: String,
        val baseGeneration: String,
        val operation: Long,
        val previousConfig: String,
        val engine: String,
        val nodeId: String
    )

    private var startElapsedRealtime = 0L
    private var lastCalculationTime = 0L
    private var lastProxyDownTotal = 0L
    private var lastProxyUpTotal = 0L
    private var carriedProxyDown = 0L
    private var carriedProxyUp = 0L
    private var hasTrafficBaseline = false

    companion object {
        // A replacement Android service instance must finish the previous instance's cleanup
        // before opening a new core or installing another forwarding policy.
        private val coreMutex = Mutex()
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingCleanupServices = CopyOnWriteArrayList<RRVpnService>()
        @Volatile private var cleanupFailure: String? = null
        @Volatile private var resetInProgress = false

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isStarting = MutableStateFlow(false)
        val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

        private val _activeRuntimeNodeId = MutableStateFlow<String?>(null)
        val activeRuntimeNodeId: StateFlow<String?> = _activeRuntimeNodeId.asStateFlow()

        private val _activeRuntimeEngine = MutableStateFlow<String?>(null)
        /** Requested engine during startup, active engine after successful activation. */
        val activeRuntimeEngine: StateFlow<String?> = _activeRuntimeEngine.asStateFlow()

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
        private val runtimeGenerationSequence = AtomicLong(0L)

        const val EXTRA_RECOVERY_REQUEST = "EXTRA_RECOVERY_REQUEST"
        const val EXTRA_ROUTING_UPDATE_GENERATION = "EXTRA_ROUTING_UPDATE_GENERATION"
        const val EXTRA_RULE_UPDATE_CANDIDATE_GENERATION = "EXTRA_RULE_UPDATE_CANDIDATE_GENERATION"
        const val EXTRA_RULE_UPDATE_BASE_GENERATION = "EXTRA_RULE_UPDATE_BASE_GENERATION"
        const val EXTRA_RULE_UPDATE_OPERATION = "EXTRA_RULE_UPDATE_OPERATION"
        const val EXTRA_CONFIG_JSON = "EXTRA_CONFIG_JSON"
        const val EXTRA_NODE_TAG = "EXTRA_NODE_TAG"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
        const val EXTRA_HEV_BENCHMARK_SELF_TRAFFIC = "EXTRA_HEV_BENCHMARK_SELF_TRAFFIC"
        const val EXTRA_ROOT_NETWORK_GENERATION = "EXTRA_ROOT_NETWORK_GENERATION"

        /** Restart the current canonical config after a forwarding-engine preference change. */
        const val ACTION_RESTART_ACTIVE_ENGINE = "com.rr.client.action.RESTART_ACTIVE_ENGINE"
        /** Refresh the Root helper's physical DNS host routes after a debounced handoff. */
        const val ACTION_ROOT_NETWORK_CHANGED = "com.rr.client.action.ROOT_NETWORK_CHANGED"
        /** Lab-only controlled failure: stop the local data plane but keep desired-running state. */
        const val ACTION_LAB_DROP_DATA_PLANE = "com.rr.client.action.LAB_DROP_DATA_PLANE"

        private const val TAG = "RRVpnService"

        fun clearLastError() {
            _lastError.value = null
        }

        /** Real local data-plane status, not just the UI StateFlow flag. */
        fun isDataPlaneHealthy(): Boolean = serviceRef?.get()?.isDataPlaneHealthyInternal() == true

        fun currentRuntimeGeneration(): Long = serviceRef?.get()?.requestGeneration ?: -1L

        fun currentRuntimeConfig(): String? = serviceRef?.get()?.activeConfigJson

        fun currentRootInterfaceName(): String? = serviceRef?.get()?.rootEngine?.interfaceName

        fun hasRootDataPlane(): Boolean = cleanupFailure != null ||
            _activeRuntimeEngine.value == PreferencesManager.TUN_ENGINE_ROOT ||
            serviceRef?.get()?.rootEngine?.let { it.isPrepared || it.isRunning } == true ||
            pendingCleanupServices.any { it.requestedEngine == PreferencesManager.TUN_ENGINE_ROOT }

        /** App-data removal must wait for native routing rollback, not just UI disconnection. */
        suspend fun awaitStopForReset(context: Context): Boolean {
            resetInProgress = true
            VpnConnectionIntentStore.setDesiredRunning(context, false)
            var cleaned = false
            try {
                cleaned = withTimeoutOrNull(25_000L) {
                    while (true) {
                        val service = withContext(Dispatchers.Main.immediate) {
                            serviceRef?.get()?.also { it.stopVpn(persistTraffic = true) }
                        } ?: break
                        while (serviceRef?.get() === service) {
                            if (cleanupFailure != null && !service.stopping) return@withTimeoutOrNull false
                            delay(50L)
                        }
                    }
                    withContext(Dispatchers.IO) {
                        coreMutex.withLock {
                            runCatching { completePendingCleanup() }.isSuccess && cleanupFailure == null &&
                                serviceRef?.get() == null && !_isRunning.value && !_isStarting.value
                        }
                    }
                } ?: false
                return cleaned
            } finally {
                if (!cleaned) resetInProgress = false
            }
        }

        /** Called only if Android rejects app-data removal after cleanup succeeded. */
        fun cancelPendingReset() { resetInProgress = false }

        private suspend fun completePendingCleanup() {
            while (true) {
                val pending = pendingCleanupServices.firstOrNull() ?: return
                pending.stopDataPlane()
                pendingCleanupServices.remove(pending)
            }
        }
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
                    val up = status.uplinkTotal
                    val down = status.downlinkTotal
                    serviceScope.launch {
                        if (_isRunning.value && !_isStarting.value && !stopping) handleRealTrafficStatus(up, down)
                    }
                }
            },
            onServiceStopRequested = {
                serviceScope.launch {
                    VpnConnectionIntentStore.setDesiredRunning(this@RRVpnService, false)
                    stopVpn(persistTraffic = true)
                }
            },
            onServiceReloadRequested = {
                serviceScope.launch {
                    if (!stopping && VpnConnectionIntentStore.isDesiredRunning(this@RRVpnService)) {
                        activeConfigJson?.let { config ->
                            ensureForeground("$activeNodeTag · 正在重启")
                            launchCore(config, restarting = true)
                        }
                    }
                }
            }
        )
        hevEngine = HevVpnEngine(
            vpnService = this,
            workingDir = filesDir,
            onLog = { line -> Log.d(TAG, line) }
        )
        rootEngine = RootVpnEngine(this) { reason -> handleUnexpectedRootExit(reason) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand startId=$startId action=${intent?.action ?: "START"}")
        if (resetInProgress) {
            VpnConnectionIntentStore.setDesiredRunning(this, false)
            ensureForeground("RRBOX · 正在清除数据")
            stopVpn(persistTraffic = true)
            return START_NOT_STICKY
        }

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

            ACTION_ROOT_NETWORK_CHANGED -> {
                val config = activeConfigJson
                val matchesRuntime = intent.getLongExtra(EXTRA_ROOT_NETWORK_GENERATION, -1L) == requestGeneration
                if (matchesRuntime && !stopping && !_isStarting.value && _isRunning.value &&
                    activeEngine == PreferencesManager.TUN_ENGINE_ROOT &&
                    requestedEngine == PreferencesManager.TUN_ENGINE_ROOT &&
                    VpnConnectionIntentStore.isDesiredRunning(this) && !config.isNullOrBlank()
                ) {
                    ensureForeground("$activeNodeTag · Root 正在更新网络")
                    launchCore(config, restarting = true)
                } else {
                    Log.i(TAG, "Discarding Root handoff for an ended, starting or replaced runtime")
                    if (!_isRunning.value && !_isStarting.value) {
                        ensureForeground("RRBOX · 已取消网络更新")
                        stopVpn(persistTraffic = false)
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART_ACTIVE_ENGINE -> {
                val config = activeConfigJson
                if (config.isNullOrBlank()) {
                    _lastError.value = "没有当前运行配置可供切换转发引擎"
                    Log.e(TAG, _lastError.value.orEmpty())
                    ensureForeground("RRBOX · 无可用运行配置")
                    stopVpn(persistTraffic = false)
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
                if (intent.hasExtra(EXTRA_RULE_UPDATE_CANDIDATE_GENERATION)) {
                    activatePreparedRules(intent)
                    return START_NOT_STICKY
                }
                if (intent.hasExtra(EXTRA_ROUTING_UPDATE_GENERATION) &&
                    !RoutingUpdatePolicy.mayApply(
                        VpnConnectionIntentStore.isDesiredRunning(this),
                        intent.getStringExtra(EXTRA_NODE_ID), activeNodeId,
                        intent.getLongExtra(EXTRA_ROUTING_UPDATE_GENERATION, -1L), requestGeneration
                    )) {
                    Log.i(TAG, "Discarding routing update for an ended or replaced VPN session")
                    if (!_isRunning.value && !_isStarting.value) {
                        ensureForeground("RRBOX · 已取消分流更新")
                        stopVpn(persistTraffic = false)
                    }
                    return START_NOT_STICKY
                }
                if (intent.getBooleanExtra(EXTRA_RECOVERY_REQUEST, false) &&
                    !VpnConnectionIntentStore.isDesiredRunning(this)) {
                    if (!_isRunning.value && !_isStarting.value) {
                        ensureForeground("RRBOX · 已取消恢复")
                        stopVpn(persistTraffic = false)
                    }
                    return START_NOT_STICKY
                }
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
                    ensureForeground("RRBOX · 无可用运行配置")
                    stopVpn(persistTraffic = false)
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
            boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true ||
            rootEngine?.isPrepared == true || rootEngine?.isRunning == true
        val duplicateEquivalentStart = !stopping && hasLiveDataPlane &&
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
            boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true ||
            rootEngine?.isPrepared == true || rootEngine?.isRunning == true
        launchCore(configJson, restarting)
        return START_NOT_STICKY
    }

    private fun advanceRuntimeGeneration(): Long =
        runtimeGenerationSequence.incrementAndGet().also { requestGeneration = it }

    private fun activatePreparedRules(intent: Intent) {
        val candidate = intent.getStringExtra(EXTRA_RULE_UPDATE_CANDIDATE_GENERATION).orEmpty()
        val base = intent.getStringExtra(EXTRA_RULE_UPDATE_BASE_GENERATION).orEmpty()
        val operation = intent.getLongExtra(EXTRA_RULE_UPDATE_OPERATION, -1L)
        val config = intent.getStringExtra(EXTRA_CONFIG_JSON)
        val previous = activeConfigJson
        val current = RoutingUpdatePolicy.mayApply(
            VpnConnectionIntentStore.isDesiredRunning(this),
            intent.getStringExtra(EXTRA_NODE_ID), activeNodeId,
            intent.getLongExtra(EXTRA_ROUTING_UPDATE_GENERATION, -1L), requestGeneration
        )
        if (!current || stopping || !_isRunning.value || _isStarting.value ||
            operation <= 0L || candidate.isBlank() || base.isBlank() || config.isNullOrBlank() || previous.isNullOrBlank() ||
            !ChinaRuleSetManager.matchesPreparedConfig(this, candidate, config)
        ) {
            ChinaRuleSetManager.noteActivationFailure(this, candidate, "连接状态已变化，请重新更新分流规则", operation)
            Log.i(TAG, "Discarding prepared rules for an ended, starting or replaced runtime")
            if (!_isRunning.value && !_isStarting.value) {
                ensureForeground("RRBOX · 已取消分流更新")
                stopVpn(persistTraffic = false)
            }
            return
        }
        // Keep node, engine, canonical config and recovery cache tied to the live session.
        ensureForeground("$activeNodeTag · 正在应用分流规则")
        launchCore(
            stableConfigJson = config,
            restarting = true,
            ruleActivation = PreparedRuleActivation(candidate, base, operation, previous, activeEngine, activeNodeId)
        )
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
        hevBenchmarkSelfTraffic: Boolean = false,
        ruleActivation: PreparedRuleActivation? = null
    ) {
        val measurementStartedAt = SystemClock.elapsedRealtime()
        val generation = advanceRuntimeGeneration()
        stopping = false
        _lastError.value = null
        _isStarting.value = true

        val previousSession = _sessionTraffic.value
        startJob?.cancel()
        // Completion may run even when cancellation prevented the coroutine body from starting.
        val ruleUpdateCommitted = AtomicBoolean(false)
        startJob = serviceScope.launch {
            var resolvedEngine = PreferencesManager.TUN_ENGINE_SYSTEM
            var startupFailure: String? = null
            var activatedConfig = stableConfigJson
            var ruleUpdateRestored = false

            val started = withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    if (generation != requestGeneration) return@withLock false

                    try {
                        val prefs = RRApplication.instance.preferencesManager
                        resolvedEngine = ruleActivation?.engine ?: prefs.tunEngine.first()
                        requestedEngine = resolvedEngine
                        completePendingCleanup()

                        if (restarting || boxCore?.isCoreRunning() == true || hevEngine?.isRunning == true ||
                            rootEngine?.isPrepared == true || rootEngine?.isRunning == true || cleanupFailure != null
                        ) {
                            if (previousSession.durationSeconds > 0L) {
                                persistSessionOnce(previousSession)
                            }
                            stopDataPlane()
                        }
                        _activeRuntimeEngine.value = resolvedEngine

                        if (ruleActivation == null) {
                            startDataPlane(stableConfigJson, resolvedEngine, hevBenchmarkSelfTraffic)
                        } else {
                            fun current(): Boolean = !stopping && generation == requestGeneration &&
                                activeNodeId == ruleActivation.nodeId &&
                                VpnConnectionIntentStore.isDesiredRunning(this@RRVpnService)
                            when (val result = RuleUpdateActivationRunner.activate(
                                isCurrent = ::current,
                                startCandidate = { startDataPlane(stableConfigJson, resolvedEngine, false) },
                                commitCandidate = {
                                    // Stop/switch arrives on Main too: commit and canonical publication
                                    // are one non-suspending operation after the last identity check.
                                    withContext(Dispatchers.Main.immediate) {
                                        if (!current() || !isDataPlaneHealthy(resolvedEngine)) false else {
                                            ChinaRuleSetManager.commitPrepared(
                                                this@RRVpnService,
                                                ruleActivation.candidateGeneration,
                                                ruleActivation.baseGeneration,
                                                ruleActivation.operation
                                            ).also { committed ->
                                                if (committed) {
                                                    ruleUpdateCommitted.set(true)
                                                    activeConfigJson = stableConfigJson
                                                }
                                            }
                                        }
                                    }
                                },
                                stopCandidate = { stopDataPlane() },
                                restorePrevious = {
                                    startDataPlane(ruleActivation.previousConfig, resolvedEngine, false)
                                }
                            )) {
                                RuleUpdateActivationRunner.Result.Activated -> Unit
                                is RuleUpdateActivationRunner.Result.Restored -> {
                                    activatedConfig = ruleActivation.previousConfig
                                    ruleUpdateRestored = true
                                    startupFailure = "新规则未能启用，已恢复原规则：${result.candidateFailure.message}"
                                }
                                is RuleUpdateActivationRunner.Result.Failed -> throw result.failure
                                RuleUpdateActivationRunner.Result.Superseded -> {
                                    withContext(Dispatchers.Main.immediate) {
                                        ChinaRuleSetManager.noteActivationFailure(
                                            this@RRVpnService, ruleActivation.candidateGeneration,
                                            "连接状态已变化，已取消本次规则启用", ruleActivation.operation
                                        )
                                    }
                                    return@withLock false
                                }
                            }
                        }
                        true
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) {
                            runCatching { stopDataPlane() }
                            if (ruleActivation != null && !ruleUpdateCommitted.get()) {
                                withContext(Dispatchers.Main.immediate) {
                                    ChinaRuleSetManager.noteActivationFailure(
                                        this@RRVpnService, ruleActivation.candidateGeneration,
                                        "连接状态已变化，已取消本次规则启用", ruleActivation.operation
                                    )
                                }
                            }
                        }
                        throw cancelled
                    } catch (error: Throwable) {
                        startupFailure = error.message ?: error.javaClass.simpleName
                        Log.e(TAG, "$resolvedEngine data-plane start failed", error)
                        runCatching { stopDataPlane() }.onFailure { cleanup ->
                            startupFailure = "$startupFailure\nRoot 清理未确认：${cleanup.message.orEmpty()}"
                        }
                        false
                    }
                }
            }

            if (generation != requestGeneration) return@launch

            if (started && (ruleUpdateCommitted.get() || isDataPlaneHealthy(resolvedEngine))) {
                activeConfigJson = activatedConfig
                activeEngine = resolvedEngine
                sessionPersisted = false
                resetTrafficState()
                _isStarting.value = false
                _isRunning.value = true
                _activeRuntimeNodeId.value = activeNodeId.takeIf(String::isNotBlank)
                VpnConnectionIntentStore.setDesiredRunning(this@RRVpnService, true)
                if (ruleUpdateRestored && ruleActivation != null) {
                    ChinaRuleSetManager.noteActivationFailure(
                        this@RRVpnService, ruleActivation.candidateGeneration, startupFailure.orEmpty(), ruleActivation.operation
                    )
                    Log.w(TAG, startupFailure.orEmpty())
                }
                notificationMgr.updateNotification(displayNodeTag(), TrafficSpeed(), 0L)
                publishRestartMeasurement(
                    measurementStartedAt = measurementStartedAt,
                    success = true,
                    engine = activeEngine
                )
                val cacheNodeTag = activeNodeTag
                val cacheNodeId = activeNodeId
                serviceScope.launch(Dispatchers.IO) {
                    refreshRuntimeCache(activatedConfig, cacheNodeTag, cacheNodeId, generation)
                }
                Log.i(
                    TAG,
                    "RRBOX data plane started: $activeNodeTag · engine=$activeEngine" +
                        if (hevBenchmarkSelfTraffic && activeEngine == PreferencesManager.TUN_ENGINE_HEV) {
                            " · benchmark-self-route-v2.5"
                        } else {
                            ""
                        }
                )
                // A process exit immediately after commit is a runtime failure. A Root
                // callback may have arrived while _isStarting was true; replay all three
                // engines' health here, retaining the committed config for recovery.
                if (ruleUpdateCommitted.get() && !isDataPlaneHealthy(resolvedEngine)) {
                    handleUnexpectedDataPlaneExit("规则启用后数据面意外退出", resolvedEngine)
                }
            } else {
                val reason = startupFailure
                    ?: hevEngine?.lastError
                    ?: boxCore?.lastError
                    ?: if (resolvedEngine == PreferencesManager.TUN_ENGINE_HEV) {
                        "HEV 极速引擎未能启动"
                    } else {
                        "sing-box 内核未能启动"
                    }
                _lastError.value = reason
                if (ruleActivation != null) {
                    ChinaRuleSetManager.noteActivationFailure(this@RRVpnService, ruleActivation.candidateGeneration, reason, ruleActivation.operation)
                }
                Log.e(TAG, reason)
                _isStarting.value = false
                publishRestartMeasurement(
                    measurementStartedAt = measurementStartedAt,
                    success = false,
                    engine = resolvedEngine
                )

                if (hevBenchmarkSelfTraffic) {
                    val cleanup = withContext(Dispatchers.IO) {
                        coreMutex.withLock { runCatching { stopDataPlane() } }
                    }
                    if (cleanup.isFailure) {
                        _lastError.value = "$reason\nRoot 清理未确认：${cleanup.exceptionOrNull()?.message.orEmpty()}"
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
        }.also { job ->
            if (ruleActivation != null) {
                val context = applicationContext
                job.invokeOnCompletion { cause ->
                    if (ruleUpdateCommitted.get()) return@invokeOnCompletion
                    // Cancellation can happen before launch enters its body, while waiting
                    // for IO, or before coreMutex is acquired. The inner teardown catch is
                    // not reached in those cases. The manager atomically matches the pending
                    // operation and candidate, so a reused generation in a newer operation
                    // cannot be cleared by this completed job.
                    runCatching {
                        ChinaRuleSetManager.noteActivationFailure(
                            context,
                            ruleActivation.candidateGeneration,
                            if (cause is CancellationException) "连接状态已变化，已取消本次规则启用"
                            else "规则启用任务已结束，未提交候选版本",
                            ruleActivation.operation
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to finalize prepared rules activation status", error)
                    }
                }
            }
        }
    }

    /** Must be called under coreMutex after the previous data plane has been fully stopped. */
    private suspend fun startDataPlane(config: String, engine: String, hevBenchmarkSelfTraffic: Boolean) {
        when (engine) {
            PreferencesManager.TUN_ENGINE_ROOT -> {
                val root = rootEngine ?: error("Root 引擎不可用")
                val runtimeConfig = root.prepare(config)
                Libbox.checkConfig(runtimeConfig)
                check(boxCore?.startService(runtimeConfig, this@RRVpnService, root) == true) {
                    boxCore?.lastError ?: "Root sing-box 内核未能启动"
                }
                // Capture is installed only after libbox owns the TUN fd.
                root.activate()
                check(root.isRunning && boxCore?.isCoreRunning() == true) { "Root 数据面未完成激活" }
            }
            PreferencesManager.TUN_ENGINE_HEV -> {
                val runtime = HevConfigAdapter.adapt(config)
                Libbox.checkConfig(runtime.configJson)
                check(boxCore?.startService(runtime.configJson, this@RRVpnService) == true) {
                    boxCore?.lastError ?: "HEV sing-box 内核未能启动"
                }
                check(hevEngine?.start(
                    policy = runtime.perAppPolicy,
                    includeSelfForBenchmark = hevBenchmarkSelfTraffic
                ) == true) { hevEngine?.lastError ?: "HEV 极速引擎未能启动" }
                check(boxCore?.isCoreRunning() == true && hevEngine?.isRunning == true) { "HEV 数据面未完成激活" }
            }
            else -> {
                check(boxCore?.startService(config, this@RRVpnService) == true) {
                    boxCore?.lastError ?: "sing-box 内核未能启动"
                }
                check(boxCore?.isCoreRunning() == true) { "sing-box 数据面未完成激活" }
            }
        }
    }

    private suspend fun refreshRuntimeCache(stableConfigJson: String, nodeTag: String, nodeId: String, generation: Long) {
        runCatching {
            val prefs = RRApplication.instance.preferencesManager
            val perAppMode = prefs.perAppMode.first()
            val selectedPackages = when (perAppMode) {
                PerAppPolicyResolver.MODE_ALLOW_LIST -> prefs.proxySelectedAppPackages.first()
                PerAppPolicyResolver.MODE_DISALLOW_LIST -> prefs.bypassSelectedAppPackages.first()
                else -> emptySet()
            }
            val state = VpnRuntimeState(
                configJson = stableConfigJson,
                nodeTag = nodeTag,
                nodeId = nodeId,
                perAppMode = perAppMode,
                selectedPackages = selectedPackages,
                smartRouting = prefs.smartRouting.first(),
                fastForwarding = prefs.fastForwarding.first()
            )
            // Preference reads above suspend. Publish only after rechecking the final
            // session identity, serialized with Main's stop/switch and rule commit.
            withContext(Dispatchers.Main.immediate) {
                if (generation == requestGeneration && activeConfigJson == stableConfigJson &&
                    VpnConnectionIntentStore.isDesiredRunning(this@RRVpnService)
                ) VpnRuntimeStateStore(this@RRVpnService).save(state)
            }
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

    private suspend fun stopDataPlane() {
        // Close the core's TUN descriptor even if the helper cannot confirm rollback.
        // A failed rollback still propagates and blocks a replacement data plane or app wipe.
        val rootFailure = runCatching { rootEngine?.stop() }.exceptionOrNull()
        try {
            hevEngine?.stop()
        } finally {
            boxCore?.stopService()
        }
        if (rootFailure != null) {
            cleanupFailure = rootFailure.message ?: "Root 路由清理未确认"
            throw rootFailure
        }
        if (pendingCleanupServices.none { it !== this }) cleanupFailure = null
    }

    private fun isDataPlaneHealthyInternal(): Boolean = isDataPlaneHealthy(activeEngine)

    private fun isDataPlaneHealthy(engine: String): Boolean = when (engine) {
        PreferencesManager.TUN_ENGINE_ROOT ->
            boxCore?.isCoreRunning() == true && rootEngine?.isRunning == true
        PreferencesManager.TUN_ENGINE_HEV ->
            boxCore?.isCoreRunning() == true && hevEngine?.isRunning == true
        else -> boxCore?.isCoreRunning() == true
    }

    private fun dropDataPlaneForLab() {
        if (!VpnConnectionIntentStore.isDesiredRunning(this) || activeConfigJson.isNullOrBlank()) {
            Log.w(TAG, "Ignoring lab data-plane drop: no desired active runtime")
            return
        }
        val generation = advanceRuntimeGeneration()
        startJob?.cancel()
        startJob = null
        serviceScope.launch {
            val cleanup = withContext(Dispatchers.IO) {
                coreMutex.withLock {
                    runCatching { if (generation == requestGeneration) stopDataPlane() }
                }
            }
            if (generation != requestGeneration) return@launch
            _isStarting.value = false
            _isRunning.value = false
            _currentSpeed.value = TrafficSpeed()
            if (cleanup.isFailure) {
                _lastError.value = "恢复演练停止失败：${cleanup.exceptionOrNull()?.message.orEmpty()}"
                ensureForeground("$activeNodeTag · Root 清理未确认")
                Log.e(TAG, _lastError.value.orEmpty())
                return@launch
            }
            ensureForeground("$activeNodeTag · 恢复演练")
            Log.w(TAG, "LAB: local data plane intentionally stopped; desired-running state preserved")
        }
    }

    private fun displayNodeTag(): String = when (activeEngine) {
        PreferencesManager.TUN_ENGINE_ROOT -> "$activeNodeTag · Root"
        PreferencesManager.TUN_ENGINE_HEV -> "$activeNodeTag · HEV"
        else -> activeNodeTag
    }

    override fun onRevoke() {
        serviceScope.launch {
            // A late revoke from the old System/HEV tunnel must not stop Root startup.
            val selectedRoot = runCatching {
                RRApplication.instance.preferencesManager.tunEngine.first() == PreferencesManager.TUN_ENGINE_ROOT
            }.getOrDefault(false)
            if (requestedEngine == PreferencesManager.TUN_ENGINE_ROOT || selectedRoot) {
                Log.i(TAG, "Ignoring Android VPN revoke for Root data plane")
                return@launch
            }
            VpnConnectionIntentStore.setDesiredRunning(this@RRVpnService, false)
            _lastError.value = "Android 已撤销 VPN 权限"
            Log.w(TAG, _lastError.value.orEmpty())
            stopVpn(persistTraffic = true)
        }
        // VpnService's default implementation calls stopSelf immediately. Teardown above
        // must finish first, including a Root rollback during an engine transition.
    }

    private fun handleUnexpectedRootExit(reason: String) =
        handleUnexpectedDataPlaneExit(reason, PreferencesManager.TUN_ENGINE_ROOT)

    private fun handleUnexpectedDataPlaneExit(reason: String, expectedEngine: String) {
        serviceScope.launch {
            if (requestedEngine != expectedEngine || stopping || _isStarting.value) return@launch
            val generation = advanceRuntimeGeneration()
            startJob?.cancel()
            startJob = null
            _isRunning.value = false
            _currentSpeed.value = TrafficSpeed()
            val cleanup = withContext(Dispatchers.IO) {
                coreMutex.withLock { runCatching { stopDataPlane() } }
            }
            if (generation != requestGeneration) return@launch
            _lastError.value = if (cleanup.isSuccess) reason else
                "$reason\nRoot 清理未确认：${cleanup.exceptionOrNull()?.message.orEmpty()}"
            ensureForeground("${displayNodeTag()} · 数据面停止")
            Log.e(TAG, _lastError.value.orEmpty())
            // Retain canonical config and desired-running state for the guarded recovery path.
            // The generation advance above invalidates a pending success-path cache write;
            // persist that same committed canonical config under the recovery generation.
            val config = activeConfigJson
            if (!config.isNullOrBlank()) {
                val cacheNodeTag = activeNodeTag
                val cacheNodeId = activeNodeId
                serviceScope.launch(Dispatchers.IO) {
                    refreshRuntimeCache(config, cacheNodeTag, cacheNodeId, generation)
                }
            }
        }
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
        val generation = advanceRuntimeGeneration()
        startJob?.cancel()
        startJob = null

        val finalSession = _sessionTraffic.value
        serviceScope.launch {
            val stopped = withContext(Dispatchers.IO) {
                runCatching {
                    coreMutex.withLock {
                        if (generation != requestGeneration) return@withLock
                        completePendingCleanup()
                        if (persistTraffic) persistSessionOnce(finalSession)
                        if (generation != requestGeneration) return@withLock
                        stopDataPlane()
                    }
                }
            }
            if (generation != requestGeneration) return@launch
            if (stopped.isFailure) {
                _isStarting.value = false
                _isRunning.value = false
                _currentSpeed.value = TrafficSpeed()
                _lastError.value = "数据面清理未确认：${stopped.exceptionOrNull()?.message.orEmpty()}"
                stopping = false
                ensureForeground("$activeNodeTag · Root 清理未确认")
                Log.e(TAG, _lastError.value.orEmpty())
                return@launch
            }
            activeConfigJson = null
            activeEngine = PreferencesManager.TUN_ENGINE_SYSTEM
            requestedEngine = PreferencesManager.TUN_ENGINE_SYSTEM
            _isStarting.value = false
            _isRunning.value = false
            _activeRuntimeNodeId.value = null
            _activeRuntimeEngine.value = null
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
        advanceRuntimeGeneration()
        startJob?.cancel()
        startJob = null
        pendingCleanupServices.addIfAbsent(this)
        cleanupScope.launch {
            coreMutex.withLock {
                runCatching { completePendingCleanup() }
                    .onFailure { Log.e(TAG, "Service destruction cleanup failed", it) }
            }
        }
        _isStarting.value = false
        _isRunning.value = false
        _activeRuntimeNodeId.value = null
        _activeRuntimeEngine.value = null
        if (serviceRef?.get() === this) serviceRef = null
        serviceScope.cancel()
        super.onDestroy()
    }
}

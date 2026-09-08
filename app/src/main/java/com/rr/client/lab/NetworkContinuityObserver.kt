package com.rr.client.lab

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import com.rr.client.RRApplication
import com.rr.client.storage.PreferencesManager
import com.rr.client.vpn.NetworkContinuityMonitor
import com.rr.client.vpn.NetworkContinuityState
import com.rr.client.vpn.RRQuickTileController
import com.rr.client.vpn.RRVpnService
import com.rr.client.vpn.VpnConnectionIntentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-lifetime network continuity observer.
 *
 * Event-driven only: no periodic heartbeat packets. A physical handoff schedules one local data-plane
 * check. If the user still expects a connection and the actual System/HEV/Root data plane is
 * dead, RRBOX reuses the last validated runtime cache for a guarded recovery.
 */
object NetworkContinuityObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NetworkContinuityState())
    val state: StateFlow<NetworkContinuityState> = _state.asStateFlow()

    private val recoveryMutex = Mutex()
    private var monitor: NetworkContinuityMonitor? = null
    private var lastRecoveryAttemptElapsed = 0L
    private var handoffJob: Job? = null
    @Volatile private var latestPath: NetworkContinuityMonitor.PhysicalPath? = null
    @Volatile private var physicalFingerprint: String? = null
    @Volatile private var rootRefreshPending = false

    fun start(context: Context) {
        if (monitor != null) return
        val appContext = context.applicationContext
        var hasSeenPath = false
        monitor = NetworkContinuityMonitor(appContext) { path ->
            handoffJob?.cancel()
            latestPath = path
            if (path == null) {
                if (RRVpnService.hasRootDataPlane()) rootRefreshPending = true
                _state.value = _state.value.copy(interfaceName = "--", validated = false,
                    healthy = false, lastEvent = "物理网络已断开，等待网络恢复")
                return@NetworkContinuityMonitor
            }
            val previous = _state.value
            val hadPath = hasSeenPath
            hasSeenPath = true
            // The monitor's final two fields are validation/metering. Only physical path,
            // addresses and DNS require rebuilding Root's resolver route exceptions.
            val fingerprint = path.signature.substringBeforeLast('|').substringBeforeLast('|')
            if (physicalFingerprint != null && physicalFingerprint != fingerprint && RRVpnService.hasRootDataPlane()) {
                rootRefreshPending = true
            }
            physicalFingerprint = fingerprint
            val switchCount = previous.switchCount + if (hadPath) 1L else 0L
            val vpnWasActive = RRVpnService.isRunning.value || RRVpnService.isStarting.value
            val now = System.currentTimeMillis()

            _state.value = previous.copy(
                monitoring = true,
                transport = path.transport,
                interfaceName = path.interfaceName,
                validated = path.validated,
                switchCount = switchCount,
                lastSwitchAtMillis = if (hadPath) now else previous.lastSwitchAtMillis,
                lastEvent = if (hadPath) {
                    "物理网络切换 → ${path.transport}/${path.interfaceName}"
                } else {
                    "物理网络初始化 → ${path.transport}/${path.interfaceName}"
                }
            )
            RRLogStore.record(
                "NET_WATCH",
                "${if (hadPath) "切换" else "初始化"}: ${path.transport}/${path.interfaceName}, " +
                    "validated=${path.validated}, vpnActive=$vpnWasActive"
            )

            if (hadPath) {
                val generation = RRVpnService.currentRuntimeGeneration()
                handoffJob = scope.launch { evaluateAfterHandoff(appContext, path.signature, generation) }
            }
        }.also { it.start() }
        _state.value = _state.value.copy(monitoring = true)
    }

    private suspend fun evaluateAfterHandoff(context: Context, signature: String, generation: Long) {
        delay(HEALTH_DELAY_MS)
        if (latestPath?.signature != signature) return
        if (!RRVpnService.hasRootDataPlane() && !RRVpnService.isStarting.value) rootRefreshPending = false

        if (rootRefreshPending && latestPath?.validated == true &&
            RRVpnService.activeRuntimeEngine.value == PreferencesManager.TUN_ENGINE_ROOT
        ) {
            // A DNS update may arrive after prepare() captured the old resolver but before
            // activation. Keep this latest, cancellable handoff pending until startup settles.
            if (RRVpnService.isStarting.value) {
                withTimeoutOrNull(ROOT_START_SETTLE_MS) {
                    while (RRVpnService.isStarting.value) delay(100L)
                }
            }
            if (latestPath?.signature != signature) return
            if (RRVpnService.currentRuntimeGeneration() != generation) {
                // A newer user/core restart reads the current network itself.
                rootRefreshPending = false
            } else if (RRApplication.instance.preferencesManager.tunEngine.first() == PreferencesManager.TUN_ENGINE_ROOT &&
                RRVpnService.isRunning.value && !RRVpnService.isStarting.value &&
                RRVpnService.isDataPlaneHealthy() && VpnConnectionIntentStore.isDesiredRunning(context)
            ) {
                val refreshed = recoveryMutex.withLock {
                    runCatching {
                        context.startService(Intent(context, RRVpnService::class.java).apply {
                            action = RRVpnService.ACTION_ROOT_NETWORK_CHANGED
                            putExtra(RRVpnService.EXTRA_ROOT_NETWORK_GENERATION, generation)
                        })
                    }.isSuccess
                }
                if (refreshed) {
                    rootRefreshPending = false
                    _state.value = _state.value.copy(
                        lastHealthCheckAtMillis = System.currentTimeMillis(),
                        lastEvent = "物理网络或 DNS 已变化，正在更新 Root 接管路径"
                    )
                    RRLogStore.record("NET_WATCH", "Root 物理路径变化：请求重建当前解析器接管规则")
                    return
                }
            }
        }

        val running = RRVpnService.isRunning.value
        val starting = RRVpnService.isStarting.value
        val dataPlaneHealthy = RRVpnService.isDataPlaneHealthy()
        val desiredRunning = VpnConnectionIntentStore.isDesiredRunning(context)
        val permissionReady = permissionReady(context)
        val cooldownReady = SystemClock.elapsedRealtime() - lastRecoveryAttemptElapsed >= RECOVERY_COOLDOWN_MS

        if (dataPlaneHealthy && (running || starting)) {
            markHealthy("切换后转发数据面仍在运行")
            RRLogStore.record(
                "NET_WATCH",
                "切换后状态: running=$running starting=$starting dataPlaneHealthy=true"
            )
            return
        }

        if (!desiredRunning) {
            markHealthy("用户已主动断开，守护不触发恢复")
            RRLogStore.record(
                "NET_WATCH",
                "切换后状态: desiredRunning=false，跳过自动恢复"
            )
            return
        }

        val shouldRecover = NetworkRecoveryPolicy.shouldRecover(
            hadPhysicalPath = true,
            desiredRunning = desiredRunning,
            stateStarting = starting,
            dataPlaneHealthy = dataPlaneHealthy,
            vpnPermissionReady = permissionReady,
            cooldownReady = cooldownReady
        )

        if (!shouldRecover) {
            val detail = when {
                starting -> "引擎正在自行重建，暂不介入"
                !permissionReady -> "VPN 权限已失效，无法自动恢复"
                !cooldownReady -> "恢复冷却期内，避免重复重启"
                else -> "数据面状态异常，但未满足自动恢复安全条件"
            }
            _state.value = _state.value.copy(
                lastHealthCheckAtMillis = System.currentTimeMillis(),
                healthy = false,
                lastEvent = detail
            )
            RRLogStore.record(
                "NET_WATCH",
                "切换后状态: running=$running starting=$starting dataPlaneHealthy=$dataPlaneHealthy " +
                    "permissionReady=$permissionReady cooldownReady=$cooldownReady · $detail"
            )
            return
        }

        attemptRecovery(context, reason = "物理网络切换后数据面停止", bypassCooldown = false)
    }

    /**
     * Controlled end-to-end recovery drill for Network Lab.
     * It intentionally stops only the local data plane, preserves the user's desired-running state,
     * and then uses the exact same recovery path as a real failed handoff.
     */
    suspend fun runRecoveryDrill(context: Context): Result<String> = runCatching {
        val appContext = context.applicationContext
        check(RRVpnService.isRunning.value && !RRVpnService.isStarting.value) {
            "请先连接节点，且等待当前启动完成"
        }
        check(RRVpnService.isDataPlaneHealthy()) { "当前数据面本身就不健康，不能开始演练" }
        check(VpnConnectionIntentStore.isDesiredRunning(appContext)) { "当前连接意图不是保持在线" }
        check(permissionReady(appContext)) { "VPN 权限不可用" }

        _state.value = _state.value.copy(
            healthy = true,
            lastEvent = "恢复演练：正在主动暂停本地数据面"
        )
        RRLogStore.record("NET_WATCH", "恢复演练开始：主动暂停本地数据面")

        appContext.startService(
            Intent(appContext, RRVpnService::class.java).apply {
                action = RRVpnService.ACTION_LAB_DROP_DATA_PLANE
            }
        )

        val dropped = withTimeoutOrNull(2_500L) {
            while (RRVpnService.isDataPlaneHealthy() || RRVpnService.isRunning.value) {
                delay(50L)
            }
            true
        } == true
        check(dropped) { "演练未能在 2.5 秒内暂停数据面" }

        val recovered = attemptRecovery(
            appContext,
            reason = "手动恢复演练",
            bypassCooldown = true
        )
        check(recovered) { "自动恢复未成功，请查看 NET_WATCH / CORE 日志" }
        "恢复演练成功：数据面已重新建立"
    }

    private suspend fun attemptRecovery(
        context: Context,
        reason: String,
        bypassCooldown: Boolean
    ): Boolean = recoveryMutex.withLock {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!bypassCooldown && nowElapsed - lastRecoveryAttemptElapsed < RECOVERY_COOLDOWN_MS) {
            return@withLock false
        }
        if (!VpnConnectionIntentStore.isDesiredRunning(context)) {
            return@withLock false
        }
        if (!permissionReady(context)) {
            _state.value = _state.value.copy(
                lastHealthCheckAtMillis = System.currentTimeMillis(),
                healthy = false,
                lastEvent = "自动恢复失败：VPN 权限不可用"
            )
            return@withLock false
        }

        lastRecoveryAttemptElapsed = nowElapsed
        _state.value = _state.value.copy(
            lastHealthCheckAtMillis = System.currentTimeMillis(),
            healthy = false,
            lastEvent = "$reason，正在自动恢复"
        )
        RRLogStore.record("NET_WATCH", "$reason：开始自动恢复")
        RRVpnService.clearLastError()

        val startResult = RRQuickTileController.recoverLastRuntime(context)
        if (startResult.isFailure) {
            val message = startResult.exceptionOrNull()?.message ?: "没有可用运行缓存"
            _state.value = _state.value.copy(
                healthy = false,
                lastEvent = "自动恢复失败：$message"
            )
            RRLogStore.record("NET_WATCH", "自动恢复启动失败: $message")
            return@withLock false
        }

        val recovered = withTimeoutOrNull(RECOVERY_TIMEOUT_MS) {
            while (true) {
                if (RRVpnService.isRunning.value && RRVpnService.isDataPlaneHealthy()) return@withTimeoutOrNull true
                val error = RRVpnService.lastError.value
                if (!error.isNullOrBlank() && !RRVpnService.isStarting.value) return@withTimeoutOrNull false
                delay(100L)
            }
        } == true

        val current = _state.value
        if (recovered) {
            _state.value = current.copy(
                recoveryCount = current.recoveryCount + 1L,
                lastHealthCheckAtMillis = System.currentTimeMillis(),
                healthy = true,
                lastEvent = "自动恢复成功 · ${current.transport}/${current.interfaceName}"
            )
            RRLogStore.record(
                "NET_WATCH",
                "自动恢复成功: recoveryCount=${current.recoveryCount + 1L}, " +
                    "path=${current.transport}/${current.interfaceName}"
            )
        } else {
            val message = RRVpnService.lastError.value ?: "恢复超时"
            _state.value = current.copy(
                lastHealthCheckAtMillis = System.currentTimeMillis(),
                healthy = false,
                lastEvent = "自动恢复失败：$message"
            )
            RRLogStore.record("NET_WATCH", "自动恢复失败: $message")
        }
        recovered
    }

    private suspend fun permissionReady(context: Context): Boolean {
        val selectedEngine = RRApplication.instance.preferencesManager.tunEngine.first()
        if (selectedEngine == PreferencesManager.TUN_ENGINE_ROOT ||
            RRVpnService.activeRuntimeEngine.value == PreferencesManager.TUN_ENGINE_ROOT ||
            RRVpnService.hasRootDataPlane()
        ) return true
        return VpnService.prepare(context) == null
    }

    private fun markHealthy(event: String) {
        _state.value = _state.value.copy(
            lastHealthCheckAtMillis = System.currentTimeMillis(),
            healthy = true,
            lastEvent = event
        )
    }

    fun stop() {
        handoffJob?.cancel()
        handoffJob = null
        monitor?.stop()
        monitor = null
        latestPath = null
        physicalFingerprint = null
        rootRefreshPending = false
        _state.value = _state.value.copy(monitoring = false)
    }

    private const val HEALTH_DELAY_MS = 1_500L
    private const val RECOVERY_TIMEOUT_MS = 5_000L
    private const val RECOVERY_COOLDOWN_MS = 5_000L
    private const val ROOT_START_SETTLE_MS = 65_000L
}

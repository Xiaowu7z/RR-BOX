package com.rr.client.lab

import android.content.Context
import com.rr.client.vpn.NetworkContinuityMonitor
import com.rr.client.vpn.NetworkContinuityState
import com.rr.client.vpn.RRVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-lifetime network continuity observer.
 *
 * This is intentionally event-driven rather than a periodic network heartbeat. It records physical
 * path changes and performs a local VPN-state check after the switch, adding essentially no network
 * traffic or battery cost while still making Wi-Fi/cellular handoffs observable in logs.
 */
object NetworkContinuityObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NetworkContinuityState())
    val state: StateFlow<NetworkContinuityState> = _state.asStateFlow()

    private var monitor: NetworkContinuityMonitor? = null

    fun start(context: Context) {
        if (monitor != null) return
        val appContext = context.applicationContext
        monitor = NetworkContinuityMonitor(appContext) { path ->
            val previous = _state.value
            val hadPath = previous.interfaceName != "--"
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

            if (vpnWasActive) {
                scope.launch {
                    delay(1_500L)
                    val running = RRVpnService.isRunning.value
                    val starting = RRVpnService.isStarting.value
                    val healthy = running || starting
                    val current = _state.value
                    _state.value = current.copy(
                        lastHealthCheckAtMillis = System.currentTimeMillis(),
                        healthy = healthy,
                        lastEvent = if (healthy) {
                            "切换后 VPN 数据面仍在运行"
                        } else {
                            "切换后 VPN 状态已停止，需要进一步恢复诊断"
                        }
                    )
                    RRLogStore.record(
                        "NET_WATCH",
                        "切换后状态: running=$running starting=$starting healthy=$healthy"
                    )
                }
            }
        }.also { it.start() }
        _state.value = _state.value.copy(monitoring = true)
    }

    fun stop() {
        monitor?.stop()
        monitor = null
        _state.value = _state.value.copy(monitoring = false)
    }
}

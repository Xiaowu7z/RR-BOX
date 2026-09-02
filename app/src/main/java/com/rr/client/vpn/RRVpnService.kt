package com.rr.client.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.rr.client.RRApplication
import com.rr.client.core.BoxServiceWrapper
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSpeed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var notificationMgr: RRNotificationManager
    private var boxCore: BoxServiceWrapper? = null

    private var activeNodeTag = "Default"
    private var activeNodeId = ""

    // 基于单调时钟计算真实速率与会话时长
    private var startElapsedRealtime = 0L
    private var lastCalculationTime = 0L
    private var lastProxyDownTotal = 0L
    private var lastProxyUpTotal = 0L

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationMgr = RRNotificationManager(this)

        // 实例化 BoxServiceWrapper，并将来自底层 CommandClient 的真实流量数据接入
        boxCore = BoxServiceWrapper(
            workingDir = filesDir,
            onLogReceived = { log ->
                Log.d("RRVpnService", log)
            },
            onStatusUpdate = { statusMessage ->
                handleRealTrafficStatus(statusMessage.uplinkTotal, statusMessage.downlinkTotal)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RRNotificationManager.ACTION_STOP_VPN) {
            stopVpn()
            return START_NOT_STICKY
        }

        val configJson = intent?.getStringExtra(EXTRA_CONFIG_JSON)
        val nodeTag = intent?.getStringExtra(EXTRA_NODE_TAG) ?: "RRVPS-Node"
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID) ?: ""

        if (!configJson.isNullOrEmpty()) {
            activeNodeTag = nodeTag
            activeNodeId = nodeId
            startVpn(configJson)
        }

        return START_STICKY
    }

    private fun startVpn(configJson: String) {
        try {
            startElapsedRealtime = SystemClock.elapsedRealtime()
            lastCalculationTime = startElapsedRealtime
            lastProxyDownTotal = 0L
            lastProxyUpTotal = 0L

            // Build and show notification BEFORE starting the VPN core
            // This is required for Android 14+ (API 34+) foreground services
            val initialNotif = notificationMgr.buildNotification(activeNodeTag, TrafficSpeed(), 0L)
            startForeground(RRNotificationManager.NOTIFICATION_ID, initialNotif)

            val started = boxCore?.startService(configJson, this) ?: false
            if (started) {
                _isRunning.value = true
            } else {
                val reason = boxCore?.lastError
                    ?: "sing-box 内核未能启动"
                Toast.makeText(this, "连接失败：$reason", Toast.LENGTH_LONG).show()
                stopVpn()
            }
        } catch (e: Exception) {
            Log.e("RRVpnService", "Failed to start VPN tunnel", e)
            Toast.makeText(this, "连接失败：${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            stopVpn()
        }
    }

    /**
     * 来自 sing-box v1.14.0 CommandClient 的真实 Proxy 出站累计流量
     * 排除 direct 和 VPN 外部绕行流量
     * 基于单调时钟计算准确瞬时速率
     */
    private fun handleRealTrafficStatus(uplinkTotal: Long, downlinkTotal: Long) {
        val now = SystemClock.elapsedRealtime()
        val deltaTimeMs = now - lastCalculationTime

        if (deltaTimeMs >= 500) { // 至少半秒计算一次速率
            val downDiff = (downlinkTotal - lastProxyDownTotal).coerceAtLeast(0L)
            val upDiff = (uplinkTotal - lastProxyUpTotal).coerceAtLeast(0L)

            val downSpeed = (downDiff * 1000L) / deltaTimeMs
            val upSpeed = (upDiff * 1000L) / deltaTimeMs

            val speed = TrafficSpeed(
                uploadBytesPerSec = upSpeed,
                downloadBytesPerSec = downSpeed
            )
            _currentSpeed.value = speed

            val durationSec = (now - startElapsedRealtime) / 1000L
            val session = SessionTraffic(
                proxyDownloadTotal = downlinkTotal,
                proxyUploadTotal = uplinkTotal,
                durationSeconds = durationSec
            )
            _sessionTraffic.value = session

            notificationMgr.updateNotification(activeNodeTag, speed, durationSec)

            lastCalculationTime = now
            lastProxyDownTotal = downlinkTotal
            lastProxyUpTotal = uplinkTotal
        }
    }

    private fun stopVpn() {
        val finalSession = _sessionTraffic.value
        if (finalSession.durationSeconds > 0) {
            serviceScope.launch(Dispatchers.IO) {
                RRApplication.instance.database.trafficDao().insertTraffic(
                    TrafficHistoryEntity(
                        nodeTag = activeNodeTag,
                        proxyDownload = finalSession.proxyDownloadTotal,
                        proxyUpload = finalSession.proxyUploadTotal,
                        directDownload = 0L,
                        directUpload = 0L,
                        durationSeconds = finalSession.durationSeconds,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }

        boxCore?.stopService()
        _isRunning.value = false
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }
}
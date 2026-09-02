package com.rr.client.vpn

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rr.client.RRApplication
import com.rr.client.core.BoxServiceWrapper
import com.rr.client.core.model.ProxyNode
import com.rr.client.storage.TrafficHistoryEntity
import com.rr.client.traffic.SessionTraffic
import com.rr.client.traffic.TrafficSampler
import com.rr.client.traffic.TrafficSpeed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class RRVpnService : VpnService() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var notificationMgr: RRNotificationManager
    private var boxCore: BoxServiceWrapper? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var trafficSampler: TrafficSampler? = null

    private var activeNodeTag = "Default"
    private var activeNodeId = ""

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
        boxCore = BoxServiceWrapper(filesDir) { log ->
            Log.d("RRVpnService", log)
        }
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
            val builder = Builder()
                .setSession("RR Client")
                .setMtu(9000)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")

            tunInterface = builder.establish()
            val fd = tunInterface?.fd ?: -1

            if (fd >= 0) {
                boxCore?.startService(configJson, fd)
                _isRunning.value = true

                // Start Foreground Notification
                val initialNotif = notificationMgr.buildNotification(activeNodeTag, TrafficSpeed(), 0L)
                startForeground(RRNotificationManager.NOTIFICATION_ID, initialNotif)

                // Initialize monotonic traffic sampler
                setupTrafficSampler()
            }
        } catch (e: Exception) {
            Log.e("RRVpnService", "Failed to establish VPN tunnel", e)
            stopVpn()
        }
    }

    private fun setupTrafficSampler() {
        // Query cumulative outbound proxy bytes
        var dummyDown = 0L
        var dummyUp = 0L

        trafficSampler = TrafficSampler(
            scope = serviceScope,
            queryProxyStats = {
                // In production with native libbox: queries Libbox command client stats for "proxy" tag
                Pair(dummyDown, dummyUp)
            },
            onBatchFlush = { session ->
                serviceScope.launch(Dispatchers.IO) {
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
                }
            }
        )

        trafficSampler?.start()

        serviceScope.launch {
            trafficSampler?.currentSpeed?.collect { speed ->
                _currentSpeed.value = speed
                trafficSampler?.sessionTraffic?.value?.let { session ->
                    _sessionTraffic.value = session
                    notificationMgr.updateNotification(activeNodeTag, speed, session.durationSeconds)
                }
            }
        }
    }

    private fun stopVpn() {
        trafficSampler?.stop()
        trafficSampler = null

        boxCore?.stopService()
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        tunInterface = null

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
